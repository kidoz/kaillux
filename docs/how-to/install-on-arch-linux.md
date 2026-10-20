# Install on Arch Linux

The repository includes an Arch package definition under
`packaging/archlinux/`. The package installs the jar, a launcher, a systemd
service, and configuration under `/etc/kaillux/`.

## Prerequisites

- `base-devel`
- Java 25 (`java-runtime >= 25` at runtime, `java-environment >= 25` to build)

`makepkg -si` installs missing dependencies from the configured repositories.

## Build and install the package

From the package directory:

```bash
cd packaging/archlinux
makepkg -si
```

The default `PKGBUILD` fetches the source archive for version 1.4.0 from
GitHub. To build from a local checkout instead, change the `source` lines in
`PKGBUILD`:

```bash
source=("git+file:///path/to/kaillux")
sha256sums=('SKIP')
```

Then run `makepkg -si` again.

## Start the service

```bash
sudo systemctl enable --now kaillux
systemctl status kaillux
```

Follow the logs:

```bash
journalctl -u kaillux -f
```

## Files installed

| Path | Description |
|---|---|
| `/usr/bin/kaillux` | Launcher script |
| `/usr/share/java/kaillux/kaillux.jar` | Application jar |
| `/etc/kaillux/application.properties` | Main configuration |
| `/etc/kaillux/access.cfg` | Sample access rules |
| `/etc/kaillux/logback.xml` | Logging configuration |
| `/usr/lib/systemd/system/kaillux.service` | systemd unit |
| `/var/lib/kaillux/` | Working directory and log location |

The service runs as the unprivileged `kaillux` user with systemd hardening
enabled.

## Set the admin password

The package does not set a password. Choose one of these methods:

**systemd environment drop-in (recommended):**

```bash
sudo systemctl edit kaillux
```

Add:

```ini
[Service]
Environment=ADMIN_PASSWORD=choose-a-strong-unique-password
```

Then:

```bash
sudo systemctl restart kaillux
```

**Configuration file:**

Edit `/etc/kaillux/application.properties`:

```properties
admin.username=admin
admin.password=choose-a-strong-unique-password
```

Restrict the file if it contains the password:

```bash
sudo chmod 600 /etc/kaillux/application.properties
sudo systemctl restart kaillux
```

The dashboard is then at <http://127.0.0.1:8080/admin/>.

## Use a custom `access.cfg`

The access manager looks for `access.cfg` in the process working directory
first, then inside the packaged jar. The packaged service runs in
`/var/lib/kaillux`, so copy the sample there if you want to customize it:

```bash
sudo cp /etc/kaillux/access.cfg /var/lib/kaillux/access.cfg
sudo chown kaillux:kaillux /var/lib/kaillux/access.cfg
sudo systemctl restart kaillux
```

Restart once so the manager resolves the new path; later edits to
`/var/lib/kaillux/access.cfg` are picked up without a restart. Do not edit the
copy inside the jar. See
[Configure access control](configure-access-control.md).

## Open the firewall

For the defaults (25 users, 10 extra ports), open:

```bash
sudo firewall-cmd --permanent --add-port=27888/udp
sudo firewall-cmd --permanent --add-port=27889-27924/udp
sudo firewall-cmd --reload
```

With ufw:

```bash
sudo ufw allow 27888/udp
sudo ufw allow 27889:27924/udp
```

If you change `server.max-users` or `controllers.v086.extra-ports`, recalculate
the range as `max-users + extra-ports + 1` ports starting at
`controllers.v086.port-range-start`. See [Network ports](../reference/network-ports.md).

Do not open TCP 8080 to the public internet unless you have a deliberate reason
and a TLS-terminating proxy in front of it.

## Upgrade

After rebuilding or installing a newer package:

```bash
sudo systemctl restart kaillux
```

Check for `.pacnew` files under `/etc/kaillux/` and merge configuration changes
before restarting.
