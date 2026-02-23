<?php

/**
 * Layer Processing Worker
 *
 * Called by queue_worker.php with a layer_id payload.
 * Runs the appropriate GDAL pipeline for the layer type and generates:
 *  - XYZ raster tiles under storage/layers/{layerId}/tiles/
 *  - GeoJSON under storage/layers/{layerId}/output.geojson
 *  - metadata.json under storage/layers/{layerId}/metadata.json
 */

declare(strict_types=1);

if (!function_exists('db')) {
    require_once __DIR__ . '/../config/database.php';
}

define('WORKER_STORAGE', __DIR__ . '/../storage/layers');
define('UPLOAD_DIR',     __DIR__ . '/../storage/uploads');

/** Maximum download size in bytes (500 MB). */
define('MAX_DOWNLOAD_BYTES', 500 * 1024 * 1024);

/** Allowed file extensions for uploaded / downloaded files. */
const ALLOWED_EXTENSIONS = ['tif', 'tiff', 'pdf', 'shp', 'zip', 'geojson', 'json'];

/**
 * Process a single layer by ID.
 */
function processLayer(int $layerId): void
{
    $pdo  = db();
    $stmt = $pdo->prepare('SELECT * FROM layers WHERE id = :id');
    $stmt->execute([':id' => $layerId]);
    $layer = $stmt->fetch();

    if (!$layer) {
        fwrite(STDERR, "Layer {$layerId} not found\n");
        return;
    }

    setLayerStatus($pdo, $layerId, 'processing');

    $layerDir = WORKER_STORAGE . '/' . $layerId;
    if (!is_dir($layerDir)) {
        mkdir($layerDir . '/tiles',   0755, true);
        mkdir($layerDir . '/mbtiles', 0755, true);
    }

    try {
        switch ($layer['type']) {
            case 'wms':
                processWms($layer, $layerDir, $pdo);
                break;
            case 'wfs':
                processWfs($layer, $layerDir, $pdo);
                break;
            case 'geotiff':
                processGeoTiff($layer, $layerDir, $pdo);
                break;
            case 'geopdf':
                processGeoPdf($layer, $layerDir, $pdo);
                break;
            case 'shapefile':
                processShapefile($layer, $layerDir, $pdo);
                break;
            case 'geojson':
                processGeoJson($layer, $layerDir, $pdo);
                break;
            default:
                throw new RuntimeException("Unknown layer type: {$layer['type']}");
        }

        setLayerStatus($pdo, $layerId, 'ready');
    } catch (Throwable $e) {
        setLayerStatus($pdo, $layerId, 'error');
        fwrite(STDERR, "Error processing layer {$layerId}: {$e->getMessage()}\n");
    }
}

// ---------------------------------------------------------------------------
// Per-type pipelines
// ---------------------------------------------------------------------------

/** WMS – just store metadata; tiles are fetched on-demand by tiles.php. */
function processWms(array $layer, string $layerDir, PDO $pdo): void
{
    writeMetadata($layerDir, $layer);
    // Update bounds from WMS GetCapabilities if desired (future extension).
}

/** WFS – fetch via ogr2ogr and store as GeoJSON. */
function processWfs(array $layer, string $layerDir, PDO $pdo): void
{
    $outputPath = $layerDir . '/output.geojson';
    $sourceUrl  = $layer['source_url'];

    validateUrl($sourceUrl);

    runCommand([
        'ogr2ogr',
        '-f', 'GeoJSON',
        '-t_srs', 'EPSG:3857',
        '-simplify', '1',
        $outputPath,
        $sourceUrl,
    ]);

    writeMetadata($layerDir, $layer);

    $stmt = $pdo->prepare('UPDATE layers SET local_path = :p WHERE id = :id');
    $stmt->execute([':p' => $outputPath, ':id' => $layer['id']]);
}

/** GeoTIFF – warp, add overviews, generate XYZ tiles. */
function processGeoTiff(array $layer, string $layerDir, PDO $pdo): void
{
    $localFile  = downloadSourceFile($layer['source_url'], $layer['id']);
    $warpedFile = $layerDir . '/warped.tif';
    $tilesDir   = $layerDir . '/tiles';

    runCommand(['gdalwarp', '-t_srs', 'EPSG:3857', $localFile, $warpedFile]);
    runCommand(['gdaladdo', $warpedFile, '2', '4', '8', '16']);
    runCommand([
        'gdal2tiles.py',
        '-z', $layer['min_zoom'] . '-' . $layer['max_zoom'],
        '--processes=4',
        $warpedFile,
        $tilesDir,
    ]);

    $bounds = gdalInfoBounds($warpedFile);
    writeMetadata($layerDir, $layer, $bounds);

    $stmt = $pdo->prepare('UPDATE layers SET local_path = :p, bounds = :b WHERE id = :id');
    $stmt->execute([':p' => $warpedFile, ':b' => $bounds, ':id' => $layer['id']]);
}

/** GeoPDF – translate to TIFF first, then same as GeoTIFF. */
function processGeoPdf(array $layer, string $layerDir, PDO $pdo): void
{
    $localFile  = downloadSourceFile($layer['source_url'], $layer['id']);
    $tiffFile   = $layerDir . '/converted.tif';
    $warpedFile = $layerDir . '/warped.tif';
    $tilesDir   = $layerDir . '/tiles';

    runCommand(['gdal_translate', $localFile, $tiffFile]);
    runCommand(['gdalwarp', '-t_srs', 'EPSG:3857', $tiffFile, $warpedFile]);
    runCommand([
        'gdal2tiles.py',
        '-z', $layer['min_zoom'] . '-' . $layer['max_zoom'],
        '--processes=4',
        $warpedFile,
        $tilesDir,
    ]);

    $bounds = gdalInfoBounds($warpedFile);
    writeMetadata($layerDir, $layer, $bounds);

    $stmt = $pdo->prepare('UPDATE layers SET local_path = :p, bounds = :b WHERE id = :id');
    $stmt->execute([':p' => $warpedFile, ':b' => $bounds, ':id' => $layer['id']]);
}

/** Shapefile – reproject and export to GeoJSON. */
function processShapefile(array $layer, string $layerDir, PDO $pdo): void
{
    $localFile   = downloadSourceFile($layer['source_url'], $layer['id']);
    $outputPath  = $layerDir . '/output.geojson';

    // If the source is a ZIP, extract it first
    $shpFile = $localFile;
    if (str_ends_with(strtolower($localFile), '.zip')) {
        $extractDir = $layerDir . '/shp_extract';
        if (!is_dir($extractDir)) {
            mkdir($extractDir, 0755, true);
        }
        $zip = new ZipArchive();
        if ($zip->open($localFile) !== true) {
            throw new RuntimeException('Failed to open ZIP archive');
        }
        $zip->extractTo($extractDir);
        $zip->close();

        // Find the .shp file (search recursively through extracted directory)
        $files = findFilesRecursive($extractDir, '.shp');
        if (empty($files)) {
            throw new RuntimeException('No .shp file found in ZIP archive');
        }
        $shpFile = $files[0];
    }

    runCommand([
        'ogr2ogr',
        '-f', 'GeoJSON',
        '-t_srs', 'EPSG:3857',
        '-simplify', '1',
        $outputPath,
        $shpFile,
    ]);

    writeMetadata($layerDir, $layer);

    $stmt = $pdo->prepare('UPDATE layers SET local_path = :p WHERE id = :id');
    $stmt->execute([':p' => $outputPath, ':id' => $layer['id']]);
}

/** GeoJSON – validate, reproject if needed, store. */
function processGeoJson(array $layer, string $layerDir, PDO $pdo): void
{
    $sourceUrl  = $layer['source_url'];
    $outputPath = $layerDir . '/output.geojson';

    // If the source is a URL, download it; otherwise treat it as a local path
    if (preg_match('#^https?://#i', $sourceUrl)) {
        validateUrl($sourceUrl);
        $localFile = downloadSourceFile($sourceUrl, $layer['id']);
    } else {
        $localFile = $sourceUrl;
    }

    // Validate JSON before processing
    $raw = file_get_contents($localFile);
    if ($raw === false || !is_array(json_decode($raw, true))) {
        throw new RuntimeException('Invalid GeoJSON file');
    }

    // Reproject to EPSG:3857 via ogr2ogr (handles already-3857 inputs gracefully)
    runCommand([
        'ogr2ogr',
        '-f', 'GeoJSON',
        '-t_srs', 'EPSG:3857',
        $outputPath,
        $localFile,
    ]);

    writeMetadata($layerDir, $layer);

    $stmt = $pdo->prepare('UPDATE layers SET local_path = :p WHERE id = :id');
    $stmt->execute([':p' => $outputPath, ':id' => $layer['id']]);
}

// ---------------------------------------------------------------------------
// Utility functions
// ---------------------------------------------------------------------------

function setLayerStatus(PDO $pdo, int $layerId, string $status): void
{
    $stmt = $pdo->prepare('UPDATE layers SET status = :s WHERE id = :id');
    $stmt->execute([':s' => $status, ':id' => $layerId]);
}

/**
 * Download a remote file to the uploads directory.
 * Validates the URL, enforces max size, and checks file extension.
 */
function downloadSourceFile(string $url, int $layerId): string
{
    validateUrl($url);

    $parsed    = parse_url($url);
    $path      = $parsed['path'] ?? '';
    $ext       = strtolower(pathinfo($path, PATHINFO_EXTENSION));

    if (!in_array($ext, ALLOWED_EXTENSIONS, true)) {
        throw new RuntimeException("Disallowed file extension: {$ext}");
    }

    $destFile = UPLOAD_DIR . '/' . $layerId . '_' . basename($path);

    $ctx = stream_context_create([
        'http' => [
            'timeout'         => 120,
            'follow_location' => 1,
            'max_redirects'   => 3,
        ],
    ]);

    $in = fopen($url, 'rb', false, $ctx);
    if ($in === false) {
        throw new RuntimeException("Failed to open URL: {$url}");
    }

    $out   = fopen($destFile, 'wb');
    $bytes = 0;

    while (!feof($in)) {
        $chunk  = fread($in, 65536);
        $bytes += strlen($chunk);
        if ($bytes > MAX_DOWNLOAD_BYTES) {
            fclose($in);
            fclose($out);
            unlink($destFile);
            throw new RuntimeException("Download exceeds maximum size (" . MAX_DOWNLOAD_BYTES . " bytes)");
        }
        fwrite($out, $chunk);
    }

    fclose($in);
    fclose($out);

    return $destFile;
}

/**
 * Validate that a URL is a safe http(s) URL.
 * Blocks private/loopback addresses to prevent SSRF.
 */
function validateUrl(string $url): void
{
    if (!preg_match('#^https?://#i', $url)) {
        throw new RuntimeException("URL must use http or https scheme: {$url}");
    }

    $host = strtolower(parse_url($url, PHP_URL_HOST) ?? '');

    if ($host === '') {
        throw new RuntimeException("Could not parse host from URL: {$url}");
    }

    // Block private / loopback ranges (SSRF prevention)
    $ip = filter_var($host, FILTER_VALIDATE_IP)
        ? $host
        : (gethostbyname($host) ?: '');

    if ($ip !== '' && filter_var($ip, FILTER_VALIDATE_IP)) {
        if (
            filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE) === false
        ) {
            throw new RuntimeException("URL resolves to a private/reserved address: {$url}");
        }
    }
}

/**
 * Run a shell command safely (no shell interpolation).
 * Throws RuntimeException on non-zero exit.
 */
function runCommand(array $cmd): void
{
    // Each argument is passed as a separate array element; proc_open
    // does NOT invoke a shell, so no injection is possible.
    $descriptors = [
        0 => ['pipe', 'r'],
        1 => ['pipe', 'w'],
        2 => ['pipe', 'w'],
    ];

    $process = proc_open($cmd, $descriptors, $pipes);

    if (!is_resource($process)) {
        throw new RuntimeException('Failed to start command: ' . implode(' ', array_map('escapeshellarg', $cmd)));
    }

    fclose($pipes[0]);
    $stderr   = stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);

    $exitCode = proc_close($process);

    if ($exitCode !== 0) {
        throw new RuntimeException(
            "Command failed (exit {$exitCode}): " . implode(' ', array_map('escapeshellarg', $cmd))
            . ($stderr ? "\nSTDERR: {$stderr}" : '')
        );
    }
}

/**
 * Recursively find all files matching a given extension under $dir.
 *
 * @return string[]
 */
function findFilesRecursive(string $dir, string $extension): array
{
    $results = [];
    $items   = scandir($dir);

    foreach ($items as $item) {
        if ($item === '.' || $item === '..') {
            continue;
        }
        $path = $dir . '/' . $item;
        if (is_dir($path)) {
            $results = array_merge($results, findFilesRecursive($path, $extension));
        } elseif (str_ends_with(strtolower($item), $extension)) {
            $results[] = $path;
        }
    }

    return $results;
}

/**
 * Run gdalinfo and extract bounds as a "minx,miny,maxx,maxy" string.
 * Falls back to an empty string on failure.
 */
function gdalInfoBounds(string $filePath): string
{
    $descriptors = [
        0 => ['pipe', 'r'],
        1 => ['pipe', 'w'],
        2 => ['pipe', 'w'],
    ];

    $process = proc_open(['gdalinfo', '-json', $filePath], $descriptors, $pipes);

    if (!is_resource($process)) {
        return '';
    }

    fclose($pipes[0]);
    $output = stream_get_contents($pipes[1]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    proc_close($process);

    $info = json_decode($output, true);
    if (!is_array($info)) {
        return '';
    }

    // wgs84Extent is available in recent GDAL versions
    $coords = $info['wgs84Extent']['coordinates'][0] ?? [];
    if (count($coords) < 4) {
        return '';
    }

    $lons = array_column($coords, 0);
    $lats = array_column($coords, 1);

    return implode(',', [min($lons), min($lats), max($lons), max($lats)]);
}

/**
 * Write a metadata.json file into the layer directory.
 */
function writeMetadata(string $layerDir, array $layer, string $bounds = ''): void
{
    $meta = [
        'id'        => $layer['id'],
        'name'      => $layer['name'],
        'type'      => $layer['type'],
        'min_zoom'  => $layer['min_zoom'],
        'max_zoom'  => $layer['max_zoom'],
        'bounds'    => $bounds ?: ($layer['bounds'] ?? ''),
        'generated' => date('c'),
    ];

    file_put_contents($layerDir . '/metadata.json', json_encode($meta, JSON_PRETTY_PRINT));
}
