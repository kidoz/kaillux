# Changelog

## Unreleased

- Drop every command except the login handshake until the login completes
- Ignore UserInformation after login so the name cannot be replaced unchecked
- Count outbound message lengths in bytes, which fixes a wrong length on the wire and an overflow that ended a client's connection
- Apply the character filter to game chat and quit messages, and escape client text in the log
- Bound the log output that unauthenticated packets can trigger and log at INFO by default
- Lower `server.max-connecting-per-address` to 3 and expire a HELLO that never starts logging in after about 1.5 seconds
- Stop serializing all handshakes on one monitor and keep a failing handshake from ending the connect listener
- Count every create-game attempt, denied or not, toward `server.create-game-flood-time`
- Refuse chat, game chat, and game creation at once for a session whose address is banned temporarily
- Report temporary bans as banned access, keep the last good rules when `access.cfg` has a bad line, read it as UTF-8, and accept indented rules
- Resolve `dns:` rules to every address, keep the last good result when a lookup fails, and warn when a hostname never resolves
- Ship `access.cfg` without the localhost admin rule and warn when such a rule is loaded
- Refuse admin authentication from an address that keeps failing before the password is checked
- Send a Content Security Policy that only allows scripts from the server
- Poll only the visible view's data from the dashboard, pause while the tab is hidden, and keep the session on a transient error
- Cut and clean the kick reason instead of silently dropping it
- Validate a relay datagram before allocating a flow, limit flows per address, and expire one-datagram flows after 15 seconds
- Encode master list query values and sanitize its response before logging
- Run the container as an unprivileged user, bind the admin port to loopback in `just docker-run`, and stop shipping lint configuration in the jar
- Remove the unused service layer, narrow the SpotBugs exclusions, and fix what they hid
- Make the coverage threshold part of `check` and forward the load-test flags to the test JVM

## 1.4.0

- Add handshake admission limits and HELLO rate limiting
- Reject game-port packets from outside the handshake address
- Fix frame assembly when a player queue desyncs
- Validate admin command peer and require login
- Close the client transport when a user quits
- Return a plain 401 for the admin API and keep the generated password out of logs
- Rewrite the admin dashboard with React and Tailwind CSS
- Package the built admin UI in local and Arch Linux builds
