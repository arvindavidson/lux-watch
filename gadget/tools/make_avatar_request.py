#!/usr/bin/env python3
"""Writes docs/muse-avatar-request.md: the avatar prompt plus the files it refers
to, as one message to paste into Muse. Run it after changing the prompt,
PixelAvatar.kt or LuxPixel.kt (AvatarRequestTest fails until you do)."""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
AVATAR = "gadget/src/main/kotlin/com/luxwatch/gadget/avatar"


def request():
    def read(rel):
        with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
            return f.read().rstrip("\n")

    parts = [read("docs/muse-avatar-prompt.md")]
    for name in ("PixelAvatar.kt", "LuxPixel.kt"):
        parts.append(f"{name}:\n```kotlin\n{read(f'{AVATAR}/{name}')}\n```")
    return "\n\n".join(parts) + "\n"


if __name__ == "__main__":
    out = os.path.join(ROOT, "docs/muse-avatar-request.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write(request())
    print(f"wrote {os.path.relpath(out)}")
