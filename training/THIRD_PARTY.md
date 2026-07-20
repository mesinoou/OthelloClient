# Third-party training data

The optional training dataset is downloaded from:

- Project: `Nyanyan/OthelloAI_Textbook`
- Repository: https://github.com/Nyanyan/OthelloAI_Textbook
- Pinned commit: `ca3dbb5bd39825ea8f6c9526243e548239066873`
- Data path: `evaluation/self_play/0000000.txt` through `0000019.txt`
- Copyright: Copyright (c) 2021 Takuto Yamana
- License: MIT License; see `third_party/OthelloAI_Textbook-LICENSE.txt`

The pattern shapes are based on the article and sample implementation, but the
bitboard replay, dataset builder, NumPy trainer, split policy, quantization, and
metadata format in this repository are independent implementations.

Downloaded records and generated datasets are stored under `.training/` and
are not committed to this repository.

The combined v2 dataset also downloads the same Federation Francaise
d'Othello WTHOR archives used to build `data/opening-book.bin`:

- Source page: https://www.ffothello.org/informatique/la-base-wthor/
- Format: https://www.ffothello.org/wthor/Format_WThor.pdf
- Archives: `WTH_2001-2015.ZIP`, `WTH_2024.ZIP`, `WTH_2025.ZIP`
- Games: 58,252
- Archive SHA-256 values: see `dataset-v2.json`

The official source invites users to download the database freely and
describes evaluation tuning and opening-book preparation as intended uses. No
separate redistribution license was found. The raw ZIP/WTB files therefore
remain local under `.training/sources/wthor/` and are not redistributed by this
repository.
