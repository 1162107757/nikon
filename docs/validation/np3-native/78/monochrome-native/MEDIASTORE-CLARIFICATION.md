# Version 78 MediaStore scope clarification

During the version 79 probe review, `PhotoPresetBatch.processUnique` was confirmed to call `PhotoLiveGallery.publish` for both the original and derived result. Publication failures are captured as warnings. Therefore, executing the version 78 monochrome batch path also attempted MediaStore publication; the old probe did not independently query committed rows or compare their bytes.

The old batch report's `mediaStorePublished: false`, “no MediaStore publishing” scope text and README wording were authored scope assumptions, not measurements. They must not be used as evidence that publication did not occur. Its 184 assertions about rendering, batch completion, reuse and source identity retain their recorded meaning. The separate renderer-only run did not execute that batch path.

Original reports, their hashes and output files are preserved. This clarification corrects the interpretation without rewriting historical evidence. Version 79 adds explicit committed publication status and ContentResolver byte checks for its own fresh synthetic outputs.
