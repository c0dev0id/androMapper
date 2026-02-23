<?php

/**
 * API Entry Point / Router
 *
 * Routes:
 *   POST   /api/layers
 *   GET    /api/layers
 *   GET    /api/layers/{id}
 *   GET    /tiles/{layerId}/{z}/{x}/{y}.png
 *   GET    /geojson/{layerId}
 *   POST   /api/offline-package
 *   GET    /api/offline-package/{id}
 */

declare(strict_types=1);

require_once __DIR__ . '/../config/database.php';

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$uri    = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
$uri    = rtrim((string)$uri, '/');

// Dispatch
try {
    if (preg_match('#^/tiles/(\d+)/(\d+)/(\d+)/(\d+)\.png$#', $uri, $m)) {
        require __DIR__ . '/tiles.php';
        serveTile((int)$m[1], (int)$m[2], (int)$m[3], (int)$m[4]);
        exit;
    }

    if (preg_match('#^/geojson/(\d+)$#', $uri, $m)) {
        require __DIR__ . '/geojson.php';
        serveGeoJson((int)$m[1]);
        exit;
    }

    if ($method === 'POST' && $uri === '/api/layers') {
        require __DIR__ . '/layers.php';
        createLayer();
        exit;
    }

    if ($method === 'GET' && $uri === '/api/layers') {
        require __DIR__ . '/layers.php';
        listLayers();
        exit;
    }

    if ($method === 'GET' && preg_match('#^/api/layers/(\d+)$#', $uri, $m)) {
        require __DIR__ . '/layers.php';
        getLayer((int)$m[1]);
        exit;
    }

    if ($method === 'POST' && $uri === '/api/offline-package') {
        require __DIR__ . '/download.php';
        createOfflinePackage();
        exit;
    }

    if ($method === 'GET' && preg_match('#^/api/offline-package/(\d+)$#', $uri, $m)) {
        require __DIR__ . '/download.php';
        downloadOfflinePackage((int)$m[1]);
        exit;
    }

    http_response_code(404);
    echo json_encode(['error' => 'Not found']);
} catch (Throwable $e) {
    http_response_code(500);
    echo json_encode(['error' => 'Internal server error']);
    error_log((string)$e);
}
