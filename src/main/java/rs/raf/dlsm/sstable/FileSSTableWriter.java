package rs.raf.dlsm.sstable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import rs.raf.dlsm.bloom.BloomFilter;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Pravi SSTable u privremenom fajlu (Checkpoint 1 §17.6). Isti builder koriste flush i compaction.
 *
 * <p>Ne radi force ni rename: to radi {@link SSTableFiles#finalizeTable}. Na kraju samoproverava fajl
 * istim čitačem koji se koristi pri pokretanju.
 */
public final class FileSSTableWriter implements SSTableWriter {

    private final FileOps fileOps;

    public FileSSTableWriter(FileOps fileOps) {
        this.fileOps = fileOps;
    }

    @Override
    public SSTableMeta build(long tableId, Iterator<InternalRecord> sortedInput, Path tmpFile) throws IOException {
        if (tableId < 1) {
            throw new IllegalArgumentException("ID tabele mora biti pozitivan");
        }
        List<SSTableFormat.IndexEntry> index = new ArrayList<>();
        long[] hashes = new long[1024];
        long count = 0;
        long minSeq = Long.MAX_VALUE;
        long maxSeq = 0;
        String minKey = null;
        String prevKey = null;

        try (FileChannel ch = FileChannel.open(tmpFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            fileOps.writeFully(ch, SSTableFormat.encodeHeader(tableId), null);
            long offset = SSTableFormat.HEADER_SIZE;

            ByteBuffer block = ByteBuffer.allocate(SSTableFormat.MAX_RECORD_BYTES);
            String blockFirstKey = null;

            while (sortedInput.hasNext()) {
                InternalRecord r = sortedInput.next();
                if (prevKey != null && r.key().compareTo(prevKey) <= 0) {
                    throw new IllegalArgumentException("Ulaz nije strogo rastući po ključu: " + prevKey + " pa " + r.key());
                }
                int size = SSTableFormat.encodedSize(r);
                if (block.position() > 0 && block.position() + size > SSTableFormat.DATA_BLOCK_TARGET_BYTES) {
                    offset += writeBlock(ch, block, blockFirstKey, offset, index);
                    blockFirstKey = null;
                }
                if (blockFirstKey == null) {
                    blockFirstKey = r.key();
                }
                SSTableFormat.encodeRecord(r, block);

                if (minKey == null) {
                    minKey = r.key();
                }
                prevKey = r.key();
                minSeq = Math.min(minSeq, r.sequence());
                maxSeq = Math.max(maxSeq, r.sequence());
                long[] h = BloomFilter.hash(r.key());
                if (2 * (count + 1) > hashes.length) {
                    hashes = Arrays.copyOf(hashes, hashes.length * 2);
                }
                hashes[(int) (2 * count)] = h[0];
                hashes[(int) (2 * count + 1)] = h[1];
                count++;
            }
            if (count == 0) {
                throw new IllegalArgumentException("SSTable ne može biti prazna");
            }
            offset += writeBlock(ch, block, blockFirstKey, offset, index);
            long dataLength = offset - SSTableFormat.HEADER_SIZE;

            ByteBuffer indexBuf = SSTableFormat.encodeIndex(index, minKey, prevKey);
            int indexCrc = SSTableFormat.crc(indexBuf);
            long indexOffset = offset;
            int indexLength = indexBuf.remaining();
            fileOps.writeFully(ch, indexBuf, null);
            offset += indexLength;

            BloomFilter bloom = BloomFilter.forEntries(count);
            for (long i = 0; i < count; i++) {
                bloom.addHashed(hashes[(int) (2 * i)], hashes[(int) (2 * i + 1)]);
            }
            byte[] bits = bloom.bytes();
            ByteBuffer bloomBuf = ByteBuffer.allocate(6 + bits.length);
            bloomBuf.put(BloomFilter.ALGORITHM_SHA256_DOUBLE_HASH).put((byte) bloom.probes()).putInt(bits.length).put(bits);
            bloomBuf.flip();
            int bloomCrc = SSTableFormat.crc(bloomBuf);
            long bloomOffset = offset;
            int bloomLength = bloomBuf.remaining();
            fileOps.writeFully(ch, bloomBuf, null);

            SSTableFormat.Footer footer = new SSTableFormat.Footer(tableId, SSTableFormat.HEADER_SIZE, dataLength,
                    indexOffset, indexLength, indexCrc, bloomOffset, bloomLength, bloomCrc, count, minSeq, maxSeq,
                    index.size());
            fileOps.writeFully(ch, footer.encode(), null);
        }

        // Samoprovera: isti čitač kao pri pokretanju, sa sopstvenim brojačima.
        try (FileSSTableReader reader = FileSSTableReader.open(tmpFile, tableId, new SSTableStats())) {
            reader.validateFully();
        }
        return new SSTableMeta(tableId, SSTableMeta.fileName(tableId), minKey, prevKey, minSeq, maxSeq, count,
                Files.size(tmpFile));
    }

    private int writeBlock(FileChannel ch, ByteBuffer block, String firstKey, long offset,
                           List<SSTableFormat.IndexEntry> index) throws IOException {
        block.flip();
        int payload = block.remaining();
        ByteBuffer framed = ByteBuffer.allocate(SSTableFormat.BLOCK_HEADER_SIZE + payload);
        framed.putInt(payload).putInt(SSTableFormat.crc(block)).put(block).flip();
        int total = framed.remaining();
        fileOps.writeFully(ch, framed, null);
        index.add(new SSTableFormat.IndexEntry(firstKey, offset, total));
        block.clear();
        return total;
    }
}
