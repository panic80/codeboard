# English context counts: sources and licenses

`en_context.tsv` is an adapted collection of word-pair and word-triple
frequency data from the sources below. It is used locally on the device. The
source counts describe published text, not a user's typing. The underlying
sources may contain biases, names, misspellings, or offensive terms.

## Leipzig Corpora Collection word pairs

University of Leipzig, Leipzig Corpora Collection, `eng_web_2014_1M`, a
sample of one million English web sentences:
https://wortschatz.uni-leipzig.de/ . The redistributor below describes the
downloadable corpus as Creative Commons Attribution (CC BY) without stating
a version. Other descriptions of Leipzig downloads identify CC BY 4.0; its
full text is included in `LICENSE-CC-BY-4.0.txt` and at
https://creativecommons.org/licenses/by/4.0/ . The Leipzig terms page was
not directly accessible when this asset was prepared, so the precise version
for this corpus was not independently verified. The license disclaimer
applies; the material is provided without warranties. This is the downloadable
corpus, not a query against the Leipzig website or web service, which can have
different terms.

Citation requested by the source: Dirk Goldhahn, Thomas Eckart, and Uwe
Quasthoff. 2012. “Building Large Monolingual Dictionaries at the Leipzig
Corpora Collection: From 100 to 200 Languages.” Proceedings of LREC 2012.
https://aclanthology.org/L12-1154/ .

The pair table is adapted from `synth-inc/predictive-bigrams`, commit
`76001baff94965b9cde7e07a4b0f007ead013a1d`, which joins the corpus's
next-neighbour co-occurrences to word spellings and retains its 50,000 most
frequent eligible rows:

https://github.com/synth-inc/predictive-bigrams/blob/76001baff94965b9cde7e07a4b0f007ead013a1d/data/en_bigrams_50k.txt

Source SHA-256:
`26ddb1797a08c653ecce720e57c9e8e9fa72c7400989885496e05b16ba1a588b`.
The repository documents its sources and CC BY terms in its README and
`discovery.tsv`. It excludes every one-character word before truncating the
50,000 rows, an important limitation for English.

## Google Books English fiction word pairs and triples

Google Books Ngram Corpus, version 20200217, English fiction books from
2010–2019; adapted from the published cleaned frequency lists compiled by
`orgtre/google-books-ngram-frequency`:
https://github.com/orgtre/google-books-ngram-frequency . The compilation is
licensed under Creative Commons Attribution 3.0 Unported (CC BY 3.0):
https://creativecommons.org/licenses/by/3.0/ . The complete license is
included in `LICENSE-CC-BY-3.0.txt`. The license disclaimer applies; the
material is provided without warranties.

The original Google Books Ngram data are described at
https://books.google.com/ngrams/info . Cite: Jean-Baptiste Michel et al. 2011.
“Quantitative Analysis of Culture Using Millions of Digitized Books.” Science
331(6014), 176–182. https://doi.org/10.1126/science.1199644 .

Upstream compilation commit: `e20471c15a758be3362b16d07870b34df4f7ccc3`.

* `ngrams/2grams_english-fiction.csv`, 5,000 rows:
  https://github.com/orgtre/google-books-ngram-frequency/blob/e20471c15a758be3362b16d07870b34df4f7ccc3/ngrams/2grams_english-fiction.csv
  SHA-256 `49ed6f0f8c3240f333226d4a8b0659c386220e98845df57ddea0d638a2fbc813`.
* `ngrams/3grams_english-fiction.csv`, 3,000 rows:
  https://github.com/orgtre/google-books-ngram-frequency/blob/e20471c15a758be3362b16d07870b34df4f7ccc3/ngrams/3grams_english-fiction.csv
  SHA-256 `6f285b78654482f02bc035081bad15158175e6b3a8fca2d85feb28603c3d4bda`.

## Adaptation and limitations

The local file contains 45,735 pairs and 2,613 triples. We normalize case,
keep tokens matching `[a-z]+(?:'[a-z]+)*` with at most 24 characters, merge
duplicate lowercase sequences by summing counts, and sort rows by spelling.
The Books counts are divided by 10,000, rounding down. Only Books pairs whose
first word is one character are included, to fill the Leipzig subset's gap.
All retained triples are from Books. Thus pairs compared after a given prior
word use the same source, and triples are compared separately from pairs.

Both upstream sources are truncated lists. Absence means unknown, not zero
occurrences. The source row cutoffs are 28 for Leipzig, 1,028,612 for Books
pairs, and 641,802 for Books triples. Contextual correction uses positive
comparison floors of 210 for an omitted pair and 130 for an omitted triple,
in the scaled units. The Leipzig list was truncated before lowercase variants
were merged, so these floors are cautious estimates, not mathematically
guaranteed upper bounds. Counts are evidence, not probabilities. Web and
fiction text are imperfect proxies for everyday messages.

To reproduce the asset, download the three exact pinned source files above
and run from the repository root:

```sh
python3 scripts/build_english_context.py en_bigrams_50k.txt \
  2grams_english-fiction.csv 3grams_english-fiction.csv
```

The script verifies the source SHA-256 hashes before building. The resulting
`en_context.tsv` SHA-256 is
`4894d4c4ff2070cf5121cab0d775d8e65fd883d41347e95b49ac2264fdb863a4`.
