# Local release validation

These records summarize real separately-loaded official/addon MPPs in one Patcher 1.12.0 Session, using the Manager-default STRIP_FAST mode. They are not Android UI or playback tests.

The minimal and reverse runs used official Captions and its real dependencies. The defaults run used 80 compatible official defaults plus one addon. Official-only counterparts were generated for bytecode/resource comparison. The addon-only case failed explicitly and produced no APK.

The audits matched existing Java runtime bodies to the frozen addon MPE, verified the exact Boolean bridge, one network hook, four row hooks and two summaries, and confirmed the original model and non-DEX entries were unchanged. The only unrelated-looking difference was the official build timestamp, classified only after all other metadata and methods matched. The AGP-generated empty own R class was separately validated as stateless.

No APK, DEX, smali, credentials or device screenshots are included in these records. The concrete files remain local. Release artifact hashes and public source availability are verified after native publication.
