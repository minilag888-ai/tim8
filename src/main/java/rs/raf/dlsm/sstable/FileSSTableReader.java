package rs.raf.dlsm.sstable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import rs.raf.dlsm.bloom.BloomFilter;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Čitanje jedne SSTable tabele (Checkpoint 1 §11.2, §12, §17).
 *
 * <p>Pri otvaranju čita i proverava footer, zaglavlje, retki indeks i Bloom filter (sve u memoriju),
 * ali ne i blokove podataka. {@link #get} čita najviše jedan blok. Svako oštećenje je {@link IOException},
 * nikad "nije pronađeno". Iteratori bacaju {@link UncheckedIOException} sa pravim uzrokom.
 */
public final class FileSSTableReader implements SSTableReader {

    private final Path file;
    private final FileChannel ch;
    private final long fileSize;
    private final SSTableFormat.Footer footer;
    private final List<SSTableFormat.IndexEntry> index;
    private final String minKey;
    private final String maxKey;
    private final BloomFilter bloom;
    private final SSTableStats stats;

    private FileSSTableReader(Path file, FileChannel ch, long fileSize, SSTableFormat.Footer footer,
                              List<SSTableFormat.IndexEntry> index, String minKey, String maxKey,
                              BloomFilter bloom, SSTableStats stats) {
        this.file = file;
        this.ch = ch;
        this.fileSize = fileSize;
        this.footer = footer;
        this.index = index;
        this.minKey = minKey;
        this.maxKey = maxKey;
        this.bloom = bloom;
        this.stats = stats;
    }

    /** Otvara tabelu sa globalnim brojačima. */
    public static FileSSTableReader open(Path file, long expectedTableId) throws IOException {
        return open(file, expectedTableId, SSTableStats.GLOBAL);
    }

    public static FileSSTableReader open(Path file, long expectedTableId, SSTableStats stats) throws IOException {
        FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
        try {
            long size = ch.size();
            if (size < SSTableFormat.HEADER_SIZE + SSTableFormat.BLOCK_HEADER_SIZE + SSTableFormat.FOOTER_SIZE) {
                throw new SSTableCorruptionException("TRUNCATED_REGION", "SSTable je prekratka: " + size + " B");
            }
            SSTableFormat.Footer footer;
            try {
                footer = SSTableFormat.Footer.decode(
                        readFully(ch, size - SSTableFormat.FOOTER_SIZE, SSTableFormat.FOOTER_SIZE), size);
            } catch (SSTableCorruptionException e) {
                throw e;
            } catch (IOException e) {
                throw new SSTableCorruptionException("FOOTER", e.getMessage());
            }
            if (footer.tableId() != expectedTableId) {
                throw new SSTableCorruptionException("FOOTER",
                        "ID tabele u fajlu (" + footer.tableId() + ") nije očekivani " + expectedTableId);
            }
            try {
                SSTableFormat.validateHeader(readFully(ch, 0, SSTableFormat.HEADER_SIZE), footer.tableId());
            } catch (SSTableCorruptionException e) {
                throw e;
            } catch (IOException e) {
                throw new SSTableCorruptionException("HEADER", e.getMessage());
            }
            return openRegions(file, ch, size, footer, stats);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    private static FileSSTableReader openRegions(Path file, FileChannel ch, long size, SSTableFormat.Footer footer,
                                                 SSTableStats stats) throws IOException {
        List<SSTableFormat.IndexEntry> entries;
        String minKey;
        String maxKey;
        try {

            ByteBuffer idx = readFully(ch, footer.indexOffset(), footer.indexLength());
            if (SSTableFormat.crc(idx) != footer.indexCrc()) {
                throw new IOException("CRC retkog indeksa se ne poklapa");
            }
            int n = idx.getInt();
            if (n != footer.blockCount() || n < 1) {
                throw new IOException("Broj stavki indeksa (" + n + ") ne odgovara broju blokova");
            }
            entries = new ArrayList<>(n);
            long expectedOffset = footer.dataOffset();
            String prev = null;
            for (int i = 0; i < n; i++) {
                String k = SSTableFormat.getKey(idx);
                if (idx.remaining() < 12) {
                    throw new IOException("Indeks je presečen");
                }
                long off = idx.getLong();
                int len = idx.getInt();
                if (off != expectedOffset || len <= SSTableFormat.BLOCK_HEADER_SIZE
                        || off + len > footer.dataOffset() + footer.dataLength()) {
                    throw new IOException("Neispravan offset/dužina bloka " + i + " u indeksu");
                }
                if (prev != null && k.compareTo(prev) <= 0) {
                    throw new IOException("Ključevi indeksa nisu rastući");
                }
                prev = k;
                expectedOffset = off + len;
                entries.add(new SSTableFormat.IndexEntry(k, off, len));
            }
            if (expectedOffset != footer.dataOffset() + footer.dataLength()) {
                throw new IOException("Blokovi ne pokrivaju celu oblast podataka");
            }
            minKey = SSTableFormat.getKey(idx);
            maxKey = SSTableFormat.getKey(idx);
            if (idx.hasRemaining() || !minKey.equals(entries.get(0).firstKey()) || maxKey.compareTo(minKey) < 0) {
                throw new IOException("Neispravni min/max ključevi u indeksu");
            }
        } catch (SSTableCorruptionException e) {
            throw e;
        } catch (IOException e) {
            throw new SSTableCorruptionException("INDEX", e.getMessage());
        }
        BloomFilter bloom;
        try {
            ByteBuffer bl = readFully(ch, footer.bloomOffset(), footer.bloomLength());
            if (SSTableFormat.crc(bl) != footer.bloomCrc()) {
                throw new IOException("CRC Bloom filtera se ne poklapa");
            }
            byte alg = bl.get();
            int probes = Byte.toUnsignedInt(bl.get());
            int bytes = bl.getInt();
            if (alg != BloomFilter.ALGORITHM_SHA256_DOUBLE_HASH || bytes != bl.remaining()) {
                throw new IOException("Nepodržan ili neispravan Bloom filter");
            }
            byte[] bits = new byte[bytes];
            bl.get(bits);
            bloom = BloomFilter.fromBytes(bits, probes);
        } catch (SSTableCorruptionException e) {
            throw e;
        } catch (IOException e) {
            throw new SSTableCorruptionException("BLOOM", e.getMessage());
        }
        return new FileSSTableReader(file, ch, size, footer, List.copyOf(entries), minKey, maxKey, bloom, stats);
    }

    @Override
    public SSTableMeta meta() {
        return new SSTableMeta(footer.tableId(), file.getFileName().toString(), minKey, maxKey,
                footer.minSequence(), footer.maxSequence(), footer.entryCount(), fileSize);
    }

    @Override
    public Optional<InternalRecord> get(String key) throws IOException {
        if (key.compareTo(minKey) < 0 || key.compareTo(maxKey) > 0) {
            return Optional.empty();
        }
        if (!bloom.mightContain(key)) {
            stats.bloomNegativeSkips.incrementAndGet();
            return Optional.empty();
        }
        stats.bloomPositiveProbes.incrementAndGet();
        int b = floorBlock(key);
        if (b < 0) {
            return Optional.empty();
        }
        ByteBuffer payload = readBlock(b, false);
        while (payload.hasRemaining()) {
            InternalRecord r = SSTableFormat.decodeRecord(payload);
            int c = r.key().compareTo(key);
            if (c == 0) {
                return Optional.of(r);
            }
            if (c > 0) {
                break;
            }
        }
        return Optional.empty();
    }

    @Override
    public Iterator<InternalRecord> iterator(String startKeyInclusive) {
        int first = startKeyInclusive == null ? 0 : Math.max(0, floorBlock(startKeyInclusive));
        return new Iterator<>() {
            private int nextBlock = first;
            private ByteBuffer current;
            private InternalRecord pending;

            private void advance() {
                try {
                    while (pending == null) {
                        if (current == null || !current.hasRemaining()) {
                            if (nextBlock >= index.size()) {
                                return;
                            }
                            current = readBlock(nextBlock++, true);
                        }
                        InternalRecord r = SSTableFormat.decodeRecord(current);
                        if (startKeyInclusive == null || r.key().compareTo(startKeyInclusive) >= 0) {
                            pending = r;
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return pending != null;
            }

            @Override
            public InternalRecord next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                InternalRecord r = pending;
                pending = null;
                return r;
            }
        };
    }

    @Override
    public void validateFully() throws IOException {
        try {
            validateBlocks();
        } catch (SSTableCorruptionException e) {
            throw e;
        } catch (IOException e) {
            throw new SSTableCorruptionException("DATA_BLOCK", e.getMessage());
        }
    }

    private void validateBlocks() throws IOException {
        String prev = null;
        long count = 0;
        for (int b = 0; b < index.size(); b++) {
            ByteBuffer payload = readBlock(b, false);
            boolean firstInBlock = true;
            while (payload.hasRemaining()) {
                InternalRecord r = SSTableFormat.decodeRecord(payload);
                if (firstInBlock && !r.key().equals(index.get(b).firstKey())) {
                    throw new IOException("Prvi ključ bloka " + b + " ne odgovara indeksu");
                }
                firstInBlock = false;
                if (prev != null && r.key().compareTo(prev) <= 0) {
                    throw new IOException("Ključevi u SSTable nisu strogo rastući kod " + r.key());
                }
                if (r.sequence() < footer.minSequence() || r.sequence() > footer.maxSequence()) {
                    throw new IOException("Redni broj " + r.sequence() + " je van opsega iz footer-a");
                }
                if (!bloom.mightContain(r.key())) {
                    throw new SSTableCorruptionException("BLOOM", "Bloom filter ne sadrži ključ " + r.key() + " (lažno negativno)");
                }
                prev = r.key();
                count++;
            }
            if (firstInBlock) {
                throw new IOException("Prazan blok " + b);
            }
        }
        if (count != footer.entryCount()) {
            throw new IOException("Broj zapisa (" + count + ") ne odgovara footer-u (" + footer.entryCount() + ")");
        }
        if (!maxKey.equals(prev)) {
            throw new IOException("Poslednji ključ ne odgovara maxKey");
        }
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }

    public int blockCount() {
        return index.size();
    }

    /** Stavke retkog indeksa (za inspect sstable). */
    public List<SSTableFormat.IndexEntry> indexEntries() {
        return index;
    }

    public SSTableFormat.Footer footer() {
        return footer;
    }

    public long bloomBits() {
        return bloom.numBits();
    }

    public int bloomProbes() {
        return bloom.probes();
    }

    /** Poslednji blok čiji je prvi ključ &lt;= key, ili -1. Binarna pretraga retkog indeksa. */
    private int floorBlock(String key) {
        int lo = 0;
        int hi = index.size() - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (index.get(mid).firstKey().compareTo(key) <= 0) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private ByteBuffer readBlock(int b, boolean rangeScan) throws IOException {
        SSTableFormat.IndexEntry e = index.get(b);
        ByteBuffer framed = readFully(ch, e.offset(), e.length());
        stats.dataBlocksRead.incrementAndGet();
        stats.dataBytesRead.addAndGet(e.length());
        if (rangeScan) {
            stats.rangeScanBlocksRead.incrementAndGet();
        }
        int len = framed.getInt();
        int crc = framed.getInt();
        if (len != e.length() - SSTableFormat.BLOCK_HEADER_SIZE) {
            throw new SSTableCorruptionException("DATA_BLOCK", "Dužina bloka " + b + " ne odgovara indeksu (offset " + e.offset() + ")");
        }
        ByteBuffer payload = framed.slice();
        if (SSTableFormat.crc(payload) != crc) {
            throw new SSTableCorruptionException("DATA_BLOCK", "CRC bloka " + b + " se ne poklapa (offset " + e.offset() + ")");
        }
        return payload;
    }

    private static ByteBuffer readFully(FileChannel ch, long pos, int len) throws IOException {
        if (len < 0 || pos < 0 || pos + len > ch.size()) {
            throw new SSTableCorruptionException("TRUNCATED_REGION", "Čitanje van fajla: offset " + pos + ", dužina " + len);
        }
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + b.position());
            if (r < 0) {
                throw new IOException("Neočekivan kraj fajla na offset-u " + (pos + b.position()));
            }
        }
        return b.flip();
    }
}
