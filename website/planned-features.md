---
title: Vote on planned features
description: Explore proposed Mihon improvements and vote for the features you would like developed first.
---

<script setup>
import FeatureVote from './.vitepress/theme/FeatureVote.vue'
</script>

# Vote on planned features

These features are being planned and have not been implemented. Support each proposal independently to help prioritize future work. Votes do not mean a feature is available or promise a delivery date.

**Each public IP can vote once per feature. English and Chinese pages share the same totals.** People sharing a household, company, or campus public IP share that voting opportunity.

## Read-chapter deletion: add an option to defer until the next launch {#deferred-read-deletion}

**Platforms: Android, Windows, and macOS. Status: design archived; not implemented.**

Keep downloads through the current use session so there is time to change a chapter's read status. A new “Delay deletion” switch, off by default, is planned directly below automatic deletion. Its description will explain:

> Delete on the next launch. Chapters marked unread again before exiting will be kept.

When enabled:

1. Finishing a chapter and leaving the reader keeps its download.
2. Marking it unread immediately cancels its pending deletion.
3. The next launch processes downloads still in the deletion queue.

Android processes can remain alive for a long time. After **24 hours** since the last logical launch, returning to the home screen and tapping the app icon triggers another cleanup. Ordinary foreground switches, notifications, and deep links do not trigger that extra cleanup. On desktop, fully exit and reopen the app.

The switch changes the timing of automatic deletion after reading. The separate manual-mark-as-read setting keeps its own behavior, and explicit download deletion keeps its normal controls.

**Limits:** manually deleting a download also removes its pending task. Each task makes one deletion attempt; failures are not retried automatically. Disabling delayed or automatic deletion, or changing the chapter-retention choice, cancels outstanding tasks under the old rule without immediately deleting historical read downloads.

<FeatureVote feature-id="deferred-read-deletion" />

## Voting and feedback {#voting-and-feedback}

Votes count only after the server accepts them. Refreshing, clearing browser data, or switching browsers does not reset the same public IP's limit. A changed IP cannot be recognized as the same participant, so totals are a demand signal only.

An unopened or temporarily unavailable service shows its status instead of creating local vote counts. The service stores a feature-specific IP verification digest and does not publish raw IP addresses.

Share detailed use cases through [project Issues](https://github.com/AltairCardinal/mihon-desktop/issues). See [Features](./features.md) for the current public functionality.
