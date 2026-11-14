# Swipe dictionaries: sources and license

The English and Korean word-frequency dictionaries in this directory are
adapted from **FrequencyWords** by `hermitdave`:
https://github.com/hermitdave/FrequencyWords

FrequencyWords identifies its content as licensed under the Creative Commons
Attribution-ShareAlike 4.0 International license (CC BY-SA 4.0):
https://creativecommons.org/licenses/by-sa/4.0/ . The complete license is
provided in `LICENSE-CC-BY-SA-4.0.txt`. The upstream license disclaimer
applies; the material is provided without warranties. The adapted `en.tsv` and
`ko.tsv` word lists are distributed under CC BY-SA 4.0. Preserve this notice
and the license when redistributing them.

The 2018 lists were derived by FrequencyWords from the OPUS OpenSubtitles 2018
corpus, originally collected from https://www.opensubtitles.org/ . Corpus
information: https://opus.nlpl.eu/legacy/OpenSubtitles-v2018.php . OPUS asks
users of the corpus to cite:

Pierre Lison and Jörg Tiedemann. 2016. “OpenSubtitles2016: Extracting Large
Parallel Corpora from Movie and TV Subtitles.” Proceedings of the 10th
International Conference on Language Resources and Evaluation (LREC 2016).
https://aclanthology.org/L16-1147/

## Original files

Upstream commit: `525f9b560de45753a5ea01069454e72e9aa541c6`.

* `content/2018/en/en_50k.txt`:
  https://github.com/hermitdave/FrequencyWords/blob/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/en/en_50k.txt
  SHA-256: `5351ff405b1126ef555791dd4d9798a48e3e9a501a9fc481a9da957752cfb458`.
* `content/2018/ko/ko_50k.txt`:
  https://github.com/hermitdave/FrequencyWords/blob/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/ko/ko_50k.txt
  SHA-256: `8d00401b6728c8d6feeaa5455583c1a33408dd020aae4aa55609b25004d1d99d`.

## Changes

The files use headerless UTF-8 `word<TAB>frequency weight` lines. They were
filtered from the first 50,000 source rows per language, with the 2018 source
occurrence counts retained as the frequency weights. English retains 46,717
source words matching `[a-z]+` and adds 44 manually curated contractions,
for a total of 46,761 entries. The source list splits these contractions, so
we assigned synthetic prior weights of 500,000 or 100,000 to the added forms;
these are not corpus occurrence counts. English was sorted by descending
frequency weight after adding these forms. Korean retains 49,313 words made
entirely of precomposed Hangul syllables U+AC00–U+D7A3, with source ordering
and counts unchanged. A larger weight indicates a more frequent form. The
subtitle-derived forms can include names, slang, misspellings, or offensive
text.

## Keyboard vocabulary supplement

The adapted English list additionally includes 23 manually curated keyboard and
input-method terms (including autocorrect, autocomplete, emoji and Dubeolsik)
that were absent from the subtitle-derived vocabulary. Each added term has a
synthetic weight of 5,000, not a corpus occurrence count. These additions are
distributed with the adapted list under CC BY-SA 4.0.
