# Edax 4.6 benchmark engine

This directory contains the unmodified official Windows x86-64 binaries and
evaluation data from the Edax 4.6 release.

- Project: https://github.com/abulmo/edax-reversi
- Release: https://github.com/abulmo/edax-reversi/releases/tag/v4.6
- Package: `edax-4.6-MS-windows-x86.zip`
- License: GNU GPL version 3; see `LICENSE`
- Upstream version date: 2024-12-18

Edax is used only as an external benchmark opponent through its official GTP
interface. The client starts it with pondering and opening-book use disabled so
that level, thread count, and game history remain reproducible.

Default command:

```text
java -cp .build EdaxOthelloClient 127.0.0.1 25033 Edax46L6 6 1
```

The server's initial discs are the horizontal mirror of Edax's standard board.
`EdaxGtpEngine` mirrors coordinates at the protocol boundary while preserving
the server's black and white colors.
