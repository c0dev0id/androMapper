# Android Offline Map Client Specification
## Mapforge-Based Client for PHP Geospatial Normalization Server

---

# 1. Purpose

This Android application connects to a PHP + MySQL Geospatial Normalization Server.

The app:

- Displays base map (Mapforge .map file, offline)
- Displays raster tile overlays from server
- Displays GeoJSON vector overlays from server
- Downloads map areas for offline use (MBTiles)
- Manages local tile cache
- Works fully offline after download

Target scale: ≤ 100 users  
Target Android version: Android 10+ (API 29+)  
Language: Kotlin (preferred) or Java  

---

# 2. Architecture Overview

Server provides:

- Raster tiles: /tiles/{layerId}/{z}/{x}/{y}.png
- Vector layers: /geojson/{layerId}
- Offline packages: MBTiles download

Android Client Architecture:

UI Layer  
→ ViewModel  
→ Repository  
→ Network Layer (Retrofit/OkHttp)  
→ Local Storage (Room + File System)  
→ Map Renderer (Mapforge)

---

# 3. Core Components

## 3.1 Map Engine

Use:

- Mapforge Android library

Base map:

- Offline .map file stored in app storage
- Rendered using MapView + TileRendererLayer

Overlay types supported:

- Raster tile overlays (server tiles)
- Vector overlays (GeoJSON)
- Offline MBTiles overlays

---

# 4. Application Modules

## 4.1 UI Module

Screens:

- Map Screen
- Layer Manager Screen
- Offline Download Screen
- Settings Screen

Map Screen features:

- Zoom & pan
- Layer toggle
- Offline mode indicator
- Download area selection tool

---

## 4.2 Network Module

Use:

- Retrofit (REST API)
- OkHttp (tile requests)

Endpoints used:

GET /api/layers  
GET /tiles/{layerId}/{z}/{x}/{y}.png  
GET /geojson/{layerId}?bbox=...  
POST /api/offline-package  
GET /api/offline-package/{id}

All requests must support:

- Timeout handling
- Retry logic
- Graceful offline fallback

---

## 4.3 Local Storage

Use:

- Room database
- File storage for tiles
- File storage for MBTiles
- GeoJSON stored locally

Directory structure:

    /Android/data/{package}/files/
        maps/
        tiles/
            {layerId}/
        offline/
        geojson/

---

# 5. Data Models

## 5.1 Layer Model

Fields:

- id: Int
- name: String
- type: Enum (RASTER, VECTOR)
- minZoom: Int
- maxZoom: Int
- isEnabled: Boolean
- isOfflineAvailable: Boolean

---

## 5.2 Offline Package Model

Fields:

- id: Int
- layerId: Int
- minZoom: Int
- maxZoom: Int
- bbox: String
- localPath: String
- status: Enum (PENDING, DOWNLOADING, READY)

---

# 6. Map Rendering Logic

## 6.1 Base Map

- Load .map file from local storage
- Use TileRendererLayer

## 6.2 Raster Tile Overlay

Create custom TileSource:

- URL template:
  /tiles/{layerId}/{z}/{x}/{y}.png

If offline:

- Check local tile folder
- If exists → load locally
- If not → show transparent tile

## 6.3 GeoJSON Overlay

Process:

1. Fetch GeoJSON
2. Parse using Moshi or Gson
3. Convert:
   - Point → Marker
   - LineString → Polyline
   - Polygon → Polygon
4. Add to Mapforge overlay layer

Must support:

- Geometry simplification by zoom
- Style configuration (color, width)

---

# 7. Offline Download System

## 7.1 User Flow

1. User selects area (bounding box)
2. Select zoom levels
3. App sends POST /api/offline-package
4. Server prepares MBTiles
5. App downloads MBTiles
6. Store locally
7. Register as local tile source

---

## 7.2 MBTiles Integration

MBTiles is SQLite.

Open using:

- SQLiteDatabase

Query:

    SELECT tile_data FROM tiles
    WHERE zoom_level = ?
    AND tile_column = ?
    AND tile_row = ?

Convert BLOB → Bitmap → Tile

Important:

MBTiles tile_row is TMS format.  
Must flip Y:

    flippedY = (2^zoom - 1) - y

---

# 8. Tile Caching Strategy

Priority order:

1. Offline MBTiles
2. Local tile cache
3. Network tile request

Local tile cache:

- Store under:
    tiles/{layerId}/{z}/{x}/{y}.png
- Use LRU eviction
- Max size configurable (e.g., 5GB)

---

# 9. GeoJSON Handling

For performance:

- Clip by viewport
- Simplify geometry based on zoom
- Avoid loading entire large dataset
- Use background thread parsing

Optional:

- Cache GeoJSON locally
- Update every X hours

---

# 10. Offline Mode

Detect connectivity:

- If offline:
  - Disable network calls
  - Use local MBTiles only
  - Display offline indicator

App must never crash if server unreachable.

---

# 11. Permissions

Required:

- INTERNET
- ACCESS_NETWORK_STATE

Optional:

- WRITE_EXTERNAL_STORAGE (if using shared storage)

---

# 12. Security

- Use HTTPS only
- Validate SSL certificate
- API key support (if server requires)
- Prevent path traversal in tile storage

---

# 13. Performance Requirements

Target devices:

- 4GB RAM minimum
- Mid-range CPU

Rendering constraints:

- Max overlays: configurable
- Avoid >10k vector features at once
- Use background threads for parsing

---

# 14. Error Handling

Handle:

- Tile 404 → show transparent tile
- Timeout → retry once
- Corrupt MBTiles → delete & re-download
- Malformed GeoJSON → skip feature

Log errors for debugging.

---

# 15. State Management

Use:

- MVVM architecture
- ViewModel for map state
- Repository for data abstraction

Persist:

- Enabled layers
- Downloaded areas
- Last map position

---

# 16. Map Interaction Features

- Layer toggle
- Opacity control
- Zoom level display
- Current coordinate display
- Optional GPS location overlay

---

# 17. Extensibility

Future support possible for:

- Multiple servers
- Authentication tokens
- Background sync
- Incremental tile updates

---

# 18. Client–Server Contract

Client expects server to provide ONLY:

Raster:
    /tiles/{layerId}/{z}/{x}/{y}.png

Vector:
    /geojson/{layerId}

Offline:
    MBTiles file

Client must NOT handle:

- GeoTIFF
- GeoPDF
- Raw WMS
- Raw WFS

All heavy processing is server-side.

---

# 19. Design Principle

The Android app is a:

Lightweight Rendering + Offline Cache Client

It does NOT:

- Reproject data
- Convert formats
- Perform heavy GIS processing

It ONLY:

- Renders tiles
- Displays vector overlays
- Manages offline packages

---

# 20. Expected Behavior

Online:

- Load base map
- Fetch overlays from server
- Cache tiles locally

Offline:

- Load base map
- Use MBTiles
- No server communication required

---

END OF DOCUMENT
