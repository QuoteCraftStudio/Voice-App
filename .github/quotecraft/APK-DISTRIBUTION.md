# QuoteCraft APK distribution

Requested by Mike on October 12, 2026.

Keep the public GitHub repository for HTML, other app source, documentation and
necessary web assets. Publish approved Android APK downloads only through the
QuoteCraft website. Keep APK backups and signing stores in private storage,
outside public repositories and the website document root. Do not place APKs
in GitHub releases, Actions artifacts, packages or archive files.

The ignore patterns reduce accidental additions. The `QuoteCraft source only /
source-only` check examines Git index blobs and rejects Android packages,
executable packages, archives, signing stores and recognized archive/executable
headers even when renamed. Run the check locally before pushing:

```sh
python3 .github/quotecraft/check_source_only.py .
```

This check does not upload, install, modify or sign any app. Its workflow uses
only a standard Ubuntu runner for a public repository, with read-only repository
permissions, no caches, no uploaded artifacts and no external package installs.
The job skips private repositories, which need a separate cost review.

To enforce it before the official branch changes, the owner must configure a
branch rule requiring pull requests and this status check, restrict direct
updates and bypass permissions, and review who can edit workflows and approve
changes. A failed check by itself does not block an unprotected direct push.
Only Mike or a maintainer he directly authorizes may publish an official update.
Normal app use, saved data and users' own builder projects remain available.

This is a distribution policy for official releases. It does not change the
repository's existing source licence or revoke rights already granted. Public
downloads can be copied; signing protects authentic Android updates, provided
the original signing keys remain private. A file permission flag cannot make a
downloaded APK impossible to edit.

Scope limits: this check does not inspect repository history, forks, caches,
releases or Actions artifact contents. It also cannot detect every possible
encoding or concealment of an APK inside text. Do not include release or
artifact upload steps for Android packages in other workflows.
