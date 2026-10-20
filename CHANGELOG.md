# Changelog

## 1.4.0

- Add handshake admission limits and HELLO rate limiting
- Reject game-port packets from outside the handshake address
- Fix frame assembly when a player queue desyncs
- Validate admin command peer and require login
- Close the client transport when a user quits
- Return a plain 401 for the admin API and keep the generated password out of logs
- Rewrite the admin dashboard with React and Tailwind CSS
- Package the built admin UI in local and Arch Linux builds
