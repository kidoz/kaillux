# Arch Linux Package

This directory contains files for building an Arch Linux package for Kaillux.
For the full documentation set, see [the documentation index](../../docs/README.md).

## Building from local source

For development, modify the PKGBUILD to use the local source:

```bash
# Edit PKGBUILD and change source to:
source=("git+file:///path/to/kaillux")
sha256sums=('SKIP')

# Build the package
makepkg -si
```

## Building from release

The default PKGBUILD fetches source from GitHub releases:

```bash
makepkg -si
```

## Package contents

After installation:

| Path | Description |
|------|-------------|
| `/usr/bin/kaillux` | Wrapper script |
| `/usr/share/java/kaillux/kaillux.jar` | Application JAR |
| `/etc/kaillux/` | Configuration files |
| `/usr/lib/systemd/system/kaillux.service` | Systemd service |
| `/var/lib/kaillux/` | Working directory (logs) |

## Managing the service

```bash
# Start
sudo systemctl start kaillux

# Enable at boot
sudo systemctl enable kaillux

# View logs
journalctl -u kaillux -f

# Check status
systemctl status kaillux
```

## Configuration

Edit `/etc/kaillux/application.properties` to configure:

- Server name and location
- Network ports
- User limits
- Admin credentials

Changes require a service restart:

```bash
sudo systemctl restart kaillux
```

## Firewall

Open the required UDP ports:

```bash
sudo firewall-cmd --permanent --add-port=27888/udp
sudo firewall-cmd --permanent --add-port=27889-27924/udp
sudo firewall-cmd --reload
```

Or with ufw:

```bash
sudo ufw allow 27888/udp
sudo ufw allow 27889:27924/udp
```

The data-port range is `server.max-users + controllers.v086.extra-ports + 1`
ports starting at `controllers.v086.port-range-start`. The defaults (25 users,
10 extra ports) produce 27889–27924. Recalculate it if you change either value;
see [Network ports](../../docs/reference/network-ports.md).
