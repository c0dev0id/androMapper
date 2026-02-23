# Geospatial Normalization & Tile Server
## PHP + MySQL Architecture (≤100 Users, Offline-Capable Android Client)

---

# 1. Purpose

This server provides a geospatial normalization layer between external map data sources and an Android client.

It:

- Accepts external geospatial sources:
  - WMS
  - WFS
  - GeoTIFF
  - GeoPDF
  - Shapefile (BLM / MVUM)
  - GeoJSON
- Converts all inputs to:
  - XYZ raster tiles (EPSG:3857)
  - GeoJSON (EPSG:3857)
- Supports offline area downloads for Android
- Caches tiles
- Stores metadata in MySQL
- Uses PHP for API layer
- Uses GDAL for geospatial processing

Target scale: ≤ 100 users

---

# 2. Technology Stack

Core Stack:

- PHP 8.2+
- MySQL 8+
- Apache or Nginx
- GDAL (CLI tools)
- PROJ (CRS handling)
- Optional: Redis (tile caching)

Optional:

- Supervisor (background workers)
- Cron (job processing)

---

# 3. High-Level Architecture

External Sources  
→ PHP API  
→ GDAL Processing  
→ Tile Generator  
→ Tile Storage (XYZ or MBTiles)  
→ Android Client

---

# 4. Data Standards

All server outputs MUST use:

- CRS: EPSG:3857 (Web Mercator)
- Tile format: 256x256 PNG
- Vector format: GeoJSON
- Encoding: UTF-8

---

# 5. Directory Structure

    /var/www/geoserver/
    │
    ├── api/
    │   ├── index.php
    │   ├── layers.php
    │   ├── tiles.php
    │   ├── geojson.php
    │   └── download.php
    │
    ├── storage/
    │   ├── uploads/
    │   ├── layers/
    │   │   └── {layerId}/
    │   │       ├── tiles/
    │   │       ├── mbtiles/
    │   │       └── metadata.json
    │
    ├── workers/
    │   ├── process_layer.php
    │   └── queue_worker.php
    │
    └── config/
        └── database.php

---

# 6. Database Schema (MySQL)

Table: layers

    CREATE TABLE layers (
        id INT AUTO_INCREMENT PRIMARY KEY,
        name VARCHAR(255),
        type ENUM('wms','wfs','geotiff','geopdf','shapefile','geojson'),
        source_url TEXT,
        local_path TEXT,
        min_zoom INT DEFAULT 0,
        max_zoom INT DEFAULT 18,
        bounds TEXT,
        status ENUM('pending','processing','ready','error') DEFAULT 'pending',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

Table: offline_packages

    CREATE TABLE offline_packages (
        id INT AUTO_INCREMENT PRIMARY KEY,
        layer_id INT,
        min_zoom INT,
        max_zoom INT,
        bbox TEXT,
        file_path TEXT,
        status ENUM('pending','processing','ready') DEFAULT 'pending',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

Table: jobs

    CREATE TABLE jobs (
        id INT AUTO_INCREMENT PRIMARY KEY,
        type VARCHAR(50),
        payload JSON,
        status ENUM('pending','running','done') DEFAULT 'pending',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

---

# 7. Supported Layer Types & Processing Pipelines

## 7.1 WMS

Input:
- WMS URL
- Layer name

Processing:
- Store metadata
- Use tile proxy method

Tile request format:

    GET /tiles/{layerId}/{z}/{x}/{y}.png

Server logic:
1. Convert z/x/y → BBOX (EPSG:3857)
2. Generate WMS GetMap request
3. Fetch image
4. Cache tile locally
5. Return PNG

Tile math:

    n = 2^z
    lon_deg = x / n * 360 - 180
    lat_rad = arctan(sinh(pi * (1 - 2*y/n)))
    lat_deg = lat_rad * 180 / pi

---

## 7.2 WFS

Processing:

    ogr2ogr -f GeoJSON output.json input_wfs_url

- Reproject to EPSG:3857
- Simplify geometry
- Store as GeoJSON

Request:

    GET /geojson/{layerId}?bbox=minx,miny,maxx,maxy

Server:
- Clip to bbox
- Return filtered GeoJSON

---

## 7.3 GeoTIFF

Processing pipeline:

    gdalwarp -t_srs EPSG:3857 input.tif warped.tif
    gdaladdo warped.tif 2 4 8 16
    gdal2tiles.py -z 0-18 warped.tif tiles/

Store under:

    storage/layers/{layerId}/tiles/{z}/{x}/{y}.png

---

## 7.4 GeoPDF

Pipeline:

    gdal_translate input.pdf output.tif
    gdalwarp -t_srs EPSG:3857 output.tif warped.tif
    gdal2tiles.py warped.tif tiles/

---

## 7.5 Shapefile (BLM / MVUM)

Processing:

    ogr2ogr -t_srs EPSG:3857 output.shp input.shp
    ogr2ogr -f GeoJSON output.json output.shp

Optional rasterization:

    gdal_rasterize

---

## 7.6 GeoJSON

- Validate JSON
- Reproject if necessary
- Store
- Optionally simplify geometry

---

# 8. API Specification

POST /api/layers

Request:

    {
      "name": "BLM Roads",
      "type": "shapefile",
      "source_url": "https://example.com/roads.zip"
    }

Response:

    {
      "layerId": 5,
      "status": "processing"
    }

GET /api/layers  
Returns all layers.

GET /tiles/{layerId}/{z}/{x}/{y}.png  
Returns raster tile.

GET /geojson/{layerId}?bbox=...  
Returns clipped GeoJSON.

POST /api/offline-package

    {
      "layerId": 3,
      "minZoom": 8,
      "maxZoom": 14,
      "bbox": "minx,miny,maxx,maxy"
    }

GET /api/offline-package/{id}  
Returns downloadable MBTiles file.

---

# 9. Offline Download Strategy

Client sends:
- Bounding box
- Min zoom
- Max zoom

Server:
1. Compute tile ranges
2. Generate missing tiles
3. Create MBTiles database

MBTiles schema:

    CREATE TABLE tiles (
        zoom_level INTEGER,
        tile_column INTEGER,
        tile_row INTEGER,
        tile_data BLOB
    );

Return MBTiles file to Android client.

---

# 10. Tile Caching Strategy

- Store tiles on disk
- Optional Redis for memory caching
- HTTP headers:

    Cache-Control: public, max-age=86400

---

# 11. Background Processing

Cron job:

    php workers/queue_worker.php

Queue stored in jobs table.

---

# 12. Security

- Validate URLs before download
- Restrict file types
- Limit upload size (e.g., 500MB)
- Use prepared statements
- Sandbox GDAL execution
- Rate-limit tile requests

---

# 13. Performance Expectations

For ≤100 users:

- 1 VPS (4 CPU, 8GB RAM)
- SSD storage
- 200GB recommended
- No horizontal scaling required

---

# 14. Output Contract for Android

Android client receives ONLY:

Raster:

    /tiles/{layerId}/{z}/{x}/{y}.png

Vector:

    /geojson/{layerId}

Offline:

    MBTiles file

The Android app must NOT handle:

- GeoTIFF
- GeoPDF
- Raw WMS
- Raw WFS

---

# 15. Design Principle

This server is a:

Universal Geospatial Input → Mobile-Optimized Output Transformer

It isolates:

- Projection complexity
- Format complexity
- Heavy processing
- Large file handling

From the Android client.
