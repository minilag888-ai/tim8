package rs.raf.dlsm.api;

import rs.raf.dlsm.engine.StorageEngine;
import rs.raf.dlsm.maintenance.MaintenanceService;

/** Delovi procesa koji postoje tek posle uspešnog oporavka. Do tada je referenca null. */
public record NodeRuntime(StorageEngine engine, MaintenanceService maintenance) {
}
