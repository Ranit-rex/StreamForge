# Licensing and third-party notices

StreamForge is intended to be distributed as free, open-source desktop software.

## Planned libobs integration

OBS Studio and libobs are distributed under GPL-2.0-or-later. If StreamForge links to or incorporates libobs in a way that creates a derivative work, the distributed desktop application must comply with the applicable GPL obligations. This includes providing corresponding source and license notices to recipients as required by the license.

## Dependency review required before release

- libobs / OBS Studio: verify exact version, license and source-offer obligations.
- FFmpeg: build configuration affects LGPL/GPL obligations; document enabled components and codecs.
- Qt: verify the selected Qt modules, distribution model and applicable LGPL/commercial requirements.
- Platform SDKs and codecs: review redistribution terms separately.

Do not ship third-party binaries until their licenses and notices have been reviewed. Add the full license texts and a generated third-party attribution inventory before publishing release installers.

This document is a project checklist, not legal advice.
