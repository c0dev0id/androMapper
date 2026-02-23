<?php

/**
 * XYZ Tile Server
 *
 * GET /tiles/{layerId}/{z}/{x}/{y}.png
 *
 * For WMS layers: proxies the WMS server and caches the result.
 * For raster layers (GeoTIFF / GeoPDF): serves pre-generated tiles from disk.
 */

declare(strict_types=1);

if (!function_exists('db')) {
    require_once __DIR__ . '/../config/database.php';
}

define('TILE_SIZE', 256);
define('STORAGE_BASE', __DIR__ . '/../storage/layers');

/**
 * Serve a single XYZ tile.
 */
function serveTile(int $layerId, int $z, int $x, int $y): void
{
    // Validate zoom / tile coordinates
    if ($z < 0 || $z > 22) {
        http_response_code(400);
        header('Content-Type: application/json');
        echo json_encode(['error' => 'Invalid zoom level']);
        return;
    }

    $maxTile = (1 << $z) - 1;
    if ($x < 0 || $x > $maxTile || $y < 0 || $y > $maxTile) {
        http_response_code(400);
        header('Content-Type: application/json');
        echo json_encode(['error' => 'Tile coordinates out of range']);
        return;
    }

    // Fetch layer record
    $stmt = db()->prepare('SELECT * FROM layers WHERE id = :id');
    $stmt->execute([':id' => $layerId]);
    $layer = $stmt->fetch();

    if (!$layer) {
        http_response_code(404);
        header('Content-Type: application/json');
        echo json_encode(['error' => 'Layer not found']);
        return;
    }

    if ($layer['status'] !== 'ready') {
        http_response_code(503);
        header('Content-Type: application/json');
        echo json_encode(['error' => 'Layer not ready', 'status' => $layer['status']]);
        return;
    }

    $tilePath = sprintf('%s/%d/tiles/%d/%d/%d.png', STORAGE_BASE, $layerId, $z, $x, $y);

    // Check disk cache first (applies to all layer types)
    if (file_exists($tilePath)) {
        sendTileFile($tilePath);
        return;
    }

    // WMS layers are fetched on-demand and cached
    if ($layer['type'] === 'wms') {
        $png = fetchWmsTile($layer, $z, $x, $y);
        if ($png === null) {
            http_response_code(502);
            header('Content-Type: application/json');
            echo json_encode(['error' => 'Failed to fetch tile from WMS source']);
            return;
        }
        cacheTile($tilePath, $png);
        sendTileData($png);
        return;
    }

    // For pre-generated tile sets the tile simply doesn't exist yet
    http_response_code(404);
    header('Content-Type: application/json');
    echo json_encode(['error' => 'Tile not found']);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Convert z/x/y to EPSG:3857 bounding box for WMS GetMap requests.
 * Returns [minx, miny, maxx, maxy] in Web Mercator metres.
 */
function tileToWebMercatorBbox(int $z, int $x, int $y): array
{
    $earthCircumference = 20037508.3427892;
    $n = 1 << $z;

    $minX = ($x / $n) * 2 * $earthCircumference - $earthCircumference;
    $maxX = (($x + 1) / $n) * 2 * $earthCircumference - $earthCircumference;

    // Note: y=0 is the top in XYZ but WMS BBOX expects bottom-left origin
    $maxY = $earthCircumference - ($y / $n) * 2 * $earthCircumference;
    $minY = $earthCircumference - (($y + 1) / $n) * 2 * $earthCircumference;

    return [$minX, $minY, $maxX, $maxY];
}

/**
 * Fetch a tile from a WMS server.
 */
function fetchWmsTile(array $layer, int $z, int $x, int $y): ?string
{
    [$minX, $minY, $maxX, $maxY] = tileToWebMercatorBbox($z, $x, $y);

    $sourceUrl = $layer['source_url'];

    // Validate source URL to prevent SSRF
    if (!preg_match('#^https?://#i', $sourceUrl)) {
        return null;
    }

    $params = http_build_query([
        'SERVICE'     => 'WMS',
        'VERSION'     => '1.3.0',
        'REQUEST'     => 'GetMap',
        'LAYERS'      => $layer['name'],
        'STYLES'      => '',
        'CRS'         => 'EPSG:3857',
        'BBOX'        => implode(',', [$minX, $minY, $maxX, $maxY]),
        'WIDTH'       => TILE_SIZE,
        'HEIGHT'      => TILE_SIZE,
        'FORMAT'      => 'image/png',
        'TRANSPARENT' => 'TRUE',
    ]);

    $url = $sourceUrl . (strpos($sourceUrl, '?') === false ? '?' : '&') . $params;

    $ctx = stream_context_create([
        'http' => [
            'timeout'         => 15,
            'follow_location' => 1,
            'max_redirects'   => 3,
        ],
    ]);

    $data = @file_get_contents($url, false, $ctx);

    if ($data === false) {
        return null;
    }

    // Verify the response is a valid PNG image
    if (substr($data, 0, 8) !== "\x89PNG\r\n\x1a\n") {
        return null;
    }

    // Use getimagesizefromstring for a deeper structure check
    $imgInfo = @getimagesizefromstring($data);
    if ($imgInfo === false || $imgInfo[2] !== IMAGETYPE_PNG) {
        return null;
    }

    return $data;
}

/**
 * Write tile data to the local cache.
 */
function cacheTile(string $path, string $data): void
{
    $dir = dirname($path);
    if (!is_dir($dir)) {
        mkdir($dir, 0755, true);
    }
    file_put_contents($path, $data);
}

/**
 * Send a cached tile file to the client.
 */
function sendTileFile(string $path): void
{
    $data = file_get_contents($path);
    if ($data === false) {
        http_response_code(500);
        header('Content-Type: application/json');
        echo json_encode(['error' => 'Failed to read tile']);
        return;
    }
    sendTileData($data);
}

/**
 * Output PNG tile bytes with appropriate headers.
 */
function sendTileData(string $data): void
{
    header('Content-Type: image/png');
    header('Cache-Control: public, max-age=86400');
    header('Content-Length: ' . strlen($data));
    echo $data;
}
