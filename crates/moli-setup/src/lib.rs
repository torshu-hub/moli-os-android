//! Provider setup operations shared by native hosts. Callers serialize access,
//! stop their engine before mutation, protect sensitive I/O, and commit returned
//! configuration candidates using their platform transaction. No HTTP server.
pub mod configuration;
pub mod hyundai;
pub mod meross;
pub mod netatmo;
