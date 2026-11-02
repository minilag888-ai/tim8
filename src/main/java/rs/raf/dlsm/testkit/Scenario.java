package rs.raf.dlsm.testkit;

/** Jedan obavezni CP1 scenario sa stabilnim ID-jem. */
@FunctionalInterface
public interface Scenario {
    void run(Ctx c) throws Exception;
}
