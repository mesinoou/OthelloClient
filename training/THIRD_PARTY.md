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
