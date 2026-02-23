<?php

/**
 * GeoJSON API
 *
 * GET /geojson/{layerId}[?bbox=minx,miny,maxx,maxy]
 *
 * Returns GeoJSON (EPSG:3857) for the requested layer, optionally clipped
 * to the provided bounding box.
 */

declare(strict_types=1);

if (!function_exists('db')) {
    require_once __DIR__ . '/../config/database.php';
}

define('GEOJSON_STORAGE', __DIR__ . '/../storage/layers');

/**
 * Serve GeoJSON for a layer.
 */
function serveGeoJson(int $layerId): void
{
    $stmt = db()->prepare('SELECT * FROM layers WHERE id = :id');
    $stmt->execute([':id' => $layerId]);
    $layer = $stmt->fetch();

    if (!$layer) {
        http_response_code(404);
        echo json_encode(['error' => 'Layer not found']);
        return;
    }

    if ($layer['status'] !== 'ready') {
        http_response_code(503);
        echo json_encode(['error' => 'Layer not ready', 'status' => $layer['status']]);
        return;
    }

    $geojsonPath = sprintf('%s/%d/output.geojson', GEOJSON_STORAGE, $layerId);

    if (!file_exists($geojsonPath)) {
        http_response_code(404);
        echo json_encode(['error' => 'GeoJSON not found for layer']);
        return;
    }

    $bbox = parseBbox($_GET['bbox'] ?? '');

    $raw = file_get_contents($geojsonPath);
    if ($raw === false) {
        http_response_code(500);
        echo json_encode(['error' => 'Failed to read GeoJSON file']);
        return;
    }

    $geojson = json_decode($raw, true);
    if (!is_array($geojson)) {
        http_response_code(500);
        echo json_encode(['error' => 'Invalid GeoJSON stored on server']);
        return;
    }

    if ($bbox !== null) {
        $geojson = clipGeoJsonToBbox($geojson, $bbox);
    }

    header('Content-Type: application/geo+json; charset=utf-8');
    header('Cache-Control: public, max-age=3600');
    echo json_encode($geojson, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Parse a "minx,miny,maxx,maxy" bbox string.
 * Returns [minx, miny, maxx, maxy] or null on failure.
 */
function parseBbox(string $raw): ?array
{
    if ($raw === '') {
        return null;
    }

    $parts = explode(',', $raw);
    if (count($parts) !== 4) {
        return null;
    }

    $vals = array_map('floatval', $parts);

    if ($vals[0] >= $vals[2] || $vals[1] >= $vals[3]) {
        return null;
    }

    return $vals;
}

/**
 * Return only features whose bounding box intersects with $bbox.
 * This is a lightweight axis-aligned intersection filter; it does not
 * clip individual geometries at the bbox boundary.
 */
function clipGeoJsonToBbox(array $geojson, array $bbox): array
{
    if (($geojson['type'] ?? '') !== 'FeatureCollection') {
        return $geojson;
    }

    [$minX, $minY, $maxX, $maxY] = $bbox;

    $filtered = array_values(array_filter(
        $geojson['features'] ?? [],
        static function (array $feature) use ($minX, $minY, $maxX, $maxY): bool {
            $coords = extractCoordinates($feature['geometry'] ?? []);
            foreach ($coords as [$cx, $cy]) {
                if ($cx >= $minX && $cx <= $maxX && $cy >= $minY && $cy <= $maxY) {
                    return true;
                }
            }
            return false;
        }
    ));

    $geojson['features'] = $filtered;
    return $geojson;
}

/**
 * Recursively extract all [x, y] coordinate pairs from a geometry.
 *
 * @return array<array{float, float}>
 */
function extractCoordinates(array $geometry): array
{
    $type   = $geometry['type']        ?? '';
    $coords = $geometry['coordinates'] ?? [];

    switch ($type) {
        case 'Point':
            return [[$coords[0], $coords[1]]];

        case 'LineString':
        case 'MultiPoint':
            return array_map(static fn($c) => [$c[0], $c[1]], $coords);

        case 'Polygon':
        case 'MultiLineString':
            $out = [];
            foreach ($coords as $ring) {
                foreach ($ring as $c) {
                    $out[] = [$c[0], $c[1]];
                }
            }
            return $out;

        case 'MultiPolygon':
            $out = [];
            foreach ($coords as $polygon) {
                foreach ($polygon as $ring) {
                    foreach ($ring as $c) {
                        $out[] = [$c[0], $c[1]];
                    }
                }
            }
            return $out;

        case 'GeometryCollection':
            $out = [];
            foreach ($geometry['geometries'] ?? [] as $g) {
                $out = array_merge($out, extractCoordinates($g));
            }
            return $out;
    }

    return [];
}
