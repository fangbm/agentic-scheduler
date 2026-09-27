# OD-012 — Local SQLite encryption at rest: feasibility decision and POC

Status: **BLOCKED_BY_PLATFORM_COMPATIBILITY**
Date: 2026-09-27
Scope: Android, Wear OS, Windows desktop, Linux desktop; Room 3.0.3, schema v11.

## Decision

Do not implement a database-encryption feature on the current stack yet. There
is no verified, production-supported SQLCipher (or equivalent) implementation
that supplies Room 3's KMP `androidx.sqlite.SQLiteDriver` contract on all of the
required targets. Replacing a dependency declaration, retaining
`BundledSQLiteDriver`, or encrypting selected fields would not encrypt the
SQLite database file and is rejected.

This decision leaves the normal Room schema unchanged. It therefore neither
changes v11 nor assumes a business-table inventory, so it remains compatible
with D9's future v12 and Agent tables once a supported driver is selected.

## Investigated options and actual POC result

| Option | Result |
| --- | --- |
| Room 3.0.3 | Its builder exposes `setDriver(SQLiteDriver)`, the KMP driver boundary. |
| SQLCipher Android official Room recipe | Requires `net.sqlcipher.database.SupportFactory` and `openHelperFactory()`, a legacy SupportSQLite integration point. Room 3 removed that API. The legacy artifact is also deprecated by its maintainer. |
| SQLCipher for JDBC | A supported Windows/Linux package exists, but it is a `java.sql.Driver`, not an `androidx.sqlite.SQLiteDriver`; it cannot be passed to Room 3. JDBC availability requires SQLCipher Enterprise. |
| Raw SQLCipher C library / third-party KMP wrappers | The upstream C source can be built for the relevant OSes, but wiring it to Room 3 would require a new cross-platform native/JNI `SQLiteDriver`, release packaging, memory-key handling, and cryptographic compatibility ownership. That is a security-sensitive custom database driver, not an available mature integration, and is not authorized by this task. |

`Room3SqlCipherCompatibilityPocTest` is the minimal runnable POC. It reflects
the exact Room 3.0.3 runtime API used by this repository and proves both sides
of the compatibility boundary: `setDriver(SQLiteDriver)` exists and
`openHelperFactory()` does not. It also asserts that the current
`BundledSQLiteDriver` implements that contract. The POC must pass before a
vendor's claimed Room 3 adapter can be considered.

The POC's binary inspection was executed against the resolved artifacts on
2026-09-27. `javap` reported:

```text
RoomDatabase$Builder.setDriver(androidx.sqlite.SQLiteDriver)
BundledSQLiteDriver implements androidx.sqlite.SQLiteDriver
net.sqlcipher.database.SupportFactory implements
    androidx.sqlite.db.SupportSQLiteOpenHelper$Factory
```

`./gradlew :shared:database:compileTestKotlinDesktop --rerun-tasks` produced
the POC test class with JDK 17. The local Gradle test executor could not run
any desktop test because it fails before JUnit initialization with
`ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain`.
That is a local Gradle-worker infrastructure failure, not acceptance evidence;
the binary inspection above is the executed compatibility evidence.

Sources consulted on 2026-09-27:

- [Room KMP driver setup](https://developer.android.com/kotlin/multiplatform/room)
- [Room 3 migration from SupportSQLite](https://developer.android.com/blog/posts/modernizing-the-room)
- [SQLCipher Android integration](https://github.com/sqlcipher/android-database-sqlcipher)
- [SQLCipher for JDBC](https://www.zetetic.net/sqlcipher/jdbc/)
- [SQLCipher upstream encryption/export behaviour](https://github.com/sqlcipher/sqlcipher)

## Required resolution before implementation

A release can proceed only after one of these is explicitly approved and
verified against the exact pinned Room version:

1. A vendor-supported, maintained `SQLiteDriver` for Room 3/KMP on Android,
   Wear, Windows and Linux, with distributable licenses and reproducible native
   artifacts; or
2. An approved architecture change that replaces Room 3 for every required
   platform and has its own migration, D8/D9 compatibility and security review;
   or
3. An approved ADR authorizing ownership of a custom native SQLCipher driver,
   including maintenance, security review, ABI packaging and test ownership.

Until then, Android Keystore/desktop secure store must not be used as a reason
to create a new key, rename the plaintext database, or silently initialize an
empty replacement. The existing D8 SyncSpace content keys remain separate and
must never be repurposed as a database passphrase.

## Acceptance design once a driver is approved

The selected driver must demonstrate all of the following with real files,
not merely a successful build:

- a new Android, Wear, Windows and Linux database survives close/reopen and no
  known title, event data, SQL text or key is discoverable in the main, WAL,
  SHM, rollback-journal, temporary, or generated backup files;
- an independently generated 256-bit database key is protected by Android
  Keystore or the existing fail-closed DesktopPlatformSecureStore, never by
  SQLite, preferences, an ordinary file, or logs;
- missing, corrupt or inaccessible secure-store material; a wrong key; and a
  corrupt database each fail closed without creating or replacing a database;
- plaintext-to-encrypted migration uses the selected engine's authenticated
  export facility, preserves the original and a manifest until the new database
  passes integrity and application-level D7/D8 state validation, and recovers
  safely after interruption or insufficient disk space;
- migration copies the whole database file rather than a fixed table list, so
  it carries D9 schema v12 and later tables without special handling;
- old plaintext and all migration backups have an explicit user-visible,
  recoverable cleanup step. Secure deletion is best-effort only on typical
  filesystems and must not be claimed as a guarantee.

No Room schema-version increment is required solely for file encryption. A
future schema migration (including D9 v12) remains the driver/Room migration
path after the encrypted engine is open.

## Coordination note

This branch intentionally changes no Room entity, DAO, exported schema, or D8
key lifecycle. D9 can continue its v12/Agent-table work independently. The
only required coordination is to rerun the eventual approved-driver migration
and full-file validation against D9's final schema after it merges.
