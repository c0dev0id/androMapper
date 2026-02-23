# Geospatial Normalization Server Setup

This guide covers installation and configuration of the androMapper server component.

## Requirements

- **PHP 7.4+** (8.x recommended) with extensions:
  - `pdo_mysql`
  - `json`
  - `mbstring`
- **MySQL 5.7+** or MariaDB 10.3+
- **Apache 2.4+** with `mod_rewrite` enabled (or Nginx)
- **GDAL 3.x** command-line tools (`gdal_translate`, `ogr2ogr`, `gdal2tiles.py`)

## Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/c0dev0id/androMapper.git
cd androMapper/server

# 2. Create database and user
mysql -u root -p <<EOF
CREATE DATABASE geomapper CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'geomapper'@'localhost' IDENTIFIED BY 'your_secure_password';
GRANT ALL PRIVILEGES ON geomapper.* TO 'geomapper'@'localhost';
FLUSH PRIVILEGES;
EOF

# 3. Initialize schema
mysql -u root -p geomapper < schema.sql

# 4. Configure database connection
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=geomapper
export DB_USER=geomapper
export DB_PASS=your_secure_password

# 5. Create storage directories
mkdir -p storage/layers storage/uploads
chmod 755 storage storage/layers storage/uploads
```

## Configuration

### Database Configuration

Set environment variables before starting the web server:

| Variable    | Default     | Description              |
|-------------|-------------|--------------------------|
| `DB_HOST`   | `localhost` | MySQL server hostname    |
| `DB_PORT`   | `3306`      | MySQL server port        |
| `DB_NAME`   | `geomapper` | Database name            |
| `DB_USER`   | `geomapper` | Database username        |
| `DB_PASS`   | (empty)     | Database password        |

Alternatively, create `config/database.local.php` to override settings:

```php
<?php
define('DB_HOST', 'your-db-host');
define('DB_PORT', '3306');
define('DB_NAME', 'geomapper');
define('DB_USER', 'geomapper');
define('DB_PASS', 'your_secure_password');
```

### Apache Configuration

Point your DocumentRoot to the `server/` directory:

```apache
<VirtualHost *:443>
    ServerName geo.example.com
    DocumentRoot /var/www/androMapper/server

    <Directory /var/www/androMapper/server>
        AllowOverride All
        Require all granted
    </Directory>

    # SSL Configuration
    SSLEngine on
    SSLCertificateFile /etc/ssl/certs/geo.example.com.crt
    SSLCertificateKeyFile /etc/ssl/private/geo.example.com.key
</VirtualHost>
```

Enable required modules:

```bash
sudo a2enmod rewrite headers ssl
sudo systemctl restart apache2
```

### Nginx Configuration

```nginx
server {
    listen 443 ssl http2;
    server_name geo.example.com;
    root /var/www/androMapper/server;
    index api/index.php;

    ssl_certificate /etc/ssl/certs/geo.example.com.crt;
    ssl_certificate_key /etc/ssl/private/geo.example.com.key;

    location / {
        try_files $uri $uri/ /api/index.php$is_args$args;
    }

    location ~ \.php$ {
        fastcgi_pass unix:/var/run/php/php8.2-fpm.sock;
        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;
        include fastcgi_params;
    }

    # Block access to sensitive files
    location ~ ^/(config|workers)/ {
        deny all;
    }
}
```

## Background Worker Setup

The queue worker processes layer uploads and generates tiles. Choose one method:

### Option 1: Cron (Simple)

Run every minute:

```bash
# Add to crontab (crontab -e)
* * * * * php /var/www/androMapper/server/workers/queue_worker.php >> /var/log/queue_worker.log 2>&1
```

### Option 2: Systemd (Recommended)

Create `/etc/systemd/system/geomapper-worker.service`:

```ini
[Unit]
Description=GeoMapper Queue Worker
After=mysql.service

[Service]
Type=simple
User=www-data
WorkingDirectory=/var/www/androMapper/server
ExecStart=/usr/bin/php workers/queue_worker.php --daemon
Restart=always
RestartSec=5
Environment="DB_HOST=localhost"
Environment="DB_NAME=geomapper"
Environment="DB_USER=geomapper"
Environment="DB_PASS=your_secure_password"

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable geomapper-worker
sudo systemctl start geomapper-worker
```

### Option 3: Supervisor

Create `/etc/supervisor/conf.d/geomapper-worker.conf`:

```ini
[program:geomapper-worker]
command=php /var/www/androMapper/server/workers/queue_worker.php --daemon
directory=/var/www/androMapper/server
user=www-data
autostart=true
autorestart=true
stderr_logfile=/var/log/geomapper-worker.err.log
stdout_logfile=/var/log/geomapper-worker.out.log
environment=DB_HOST="localhost",DB_NAME="geomapper",DB_USER="geomapper",DB_PASS="your_password"
```

```bash
sudo supervisorctl reread
sudo supervisorctl update
sudo supervisorctl start geomapper-worker
```

## GDAL Installation

### Ubuntu/Debian

```bash
sudo apt update
sudo apt install gdal-bin python3-gdal
```

### macOS (Homebrew)

```bash
brew install gdal
```

### Verify Installation

```bash
gdalinfo --version
ogr2ogr --version
gdal2tiles.py --version
```

## API Endpoints

| Method | Endpoint                        | Description                      |
|--------|---------------------------------|----------------------------------|
| POST   | `/api/layers`                   | Register a new layer             |
| GET    | `/api/layers`                   | List all layers                  |
| GET    | `/api/layers/{id}`              | Get layer details                |
| GET    | `/tiles/{layerId}/{z}/{x}/{y}.png` | Fetch XYZ tile                |
| GET    | `/geojson/{layerId}[?bbox=...]` | Get GeoJSON (optionally clipped) |
| POST   | `/api/offline-package`          | Request offline MBTiles package  |
| GET    | `/api/offline-package/{id}`     | Download offline package         |

### Example: Register a Layer

```bash
curl -X POST https://geo.example.com/api/layers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Satellite Imagery",
    "type": "geotiff",
    "source_url": "https://example.com/data/satellite.tif",
    "min_zoom": 0,
    "max_zoom": 18
  }'
```

Supported layer types:
- `wms` - Web Map Service (proxied on-demand)
- `wfs` - Web Feature Service
- `geotiff` - GeoTIFF raster
- `geopdf` - GeoPDF document
- `shapefile` - ESRI Shapefile (ZIP archive)
- `geojson` - GeoJSON vector data

## Directory Structure

```
server/
├── api/
│   ├── index.php       # Router / entry point
│   ├── layers.php      # Layer CRUD endpoints
│   ├── tiles.php       # XYZ tile server
│   ├── geojson.php     # GeoJSON endpoint
│   └── download.php    # Offline package API
├── config/
│   └── database.php    # Database configuration
├── storage/
│   ├── layers/         # Processed layer data
│   │   └── {layerId}/
│   │       ├── tiles/  # XYZ PNG tiles
│   │       ├── mbtiles/ # MBTiles packages
│   │       └── output.geojson
│   └── uploads/        # Temporary upload storage
├── workers/
│   ├── queue_worker.php   # Job queue processor
│   └── process_layer.php  # GDAL processing pipeline
├── .htaccess           # Apache rewrite rules
└── schema.sql          # Database schema
```

## Troubleshooting

### Logs

- Apache: `/var/log/apache2/error.log`
- PHP-FPM: `/var/log/php8.2-fpm.log`
- Queue worker: `/var/log/queue_worker.log`
- Systemd: `journalctl -u geomapper-worker -f`

### Common Issues

**500 Internal Server Error**
- Check PHP error logs
- Verify database credentials
- Ensure storage directories are writable

**Layers stuck in "pending" status**
- Verify queue worker is running
- Check worker logs for GDAL errors
- Ensure GDAL tools are installed and in PATH

**Tiles not loading**
- Verify layer status is "ready"
- Check storage/layers/{id}/tiles/ directory exists
- For WMS layers, verify source URL is accessible

**Permission errors**
```bash
sudo chown -R www-data:www-data /var/www/androMapper/server/storage
sudo chmod -R 755 /var/www/androMapper/server/storage
```

## Security Recommendations

1. **Use HTTPS** - Always serve over TLS
2. **Firewall** - Restrict database port to localhost
3. **Authentication** - Add API key authentication for production
4. **Rate limiting** - Configure at reverse proxy level
5. **Input validation** - Already implemented, but review for your use case
6. **File uploads** - Limited to 500MB, validated extensions only
