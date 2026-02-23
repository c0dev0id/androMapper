-- Geospatial Normalization Server – MySQL Schema
-- Run once to initialize the database:
--   mysql -u root -p geomapper < schema.sql

CREATE TABLE IF NOT EXISTS layers (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255),
    type       ENUM('wms','wfs','geotiff','geopdf','shapefile','geojson'),
    source_url TEXT,
    local_path TEXT,
    min_zoom   INT DEFAULT 0,
    max_zoom   INT DEFAULT 18,
    bounds     TEXT,
    status     ENUM('pending','processing','ready','error') DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS offline_packages (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    layer_id   INT,
    min_zoom   INT,
    max_zoom   INT,
    bbox       TEXT,
    file_path  TEXT,
    status     ENUM('pending','processing','ready') DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (layer_id) REFERENCES layers(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS jobs (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    type       VARCHAR(50),
    payload    JSON,
    status     ENUM('pending','running','done','error') DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
