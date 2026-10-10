# Pinned FlexibleAdapter publication

This repository contains only `com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533`.
Its original JitPack AAR and JAR endpoints returned HTTP 404 during a clean Android CI build.

The AAR is an unmodified copy of the artifact already used by the accepted Android builds:

- size: 125252 bytes
- SHA-1: `9deec5a74df8efeb2dd0d7d2181b160fe2abc5fe`
- SHA-256: `41929c785c249e0395faf89fd6bb253aafd65d44d88dbeaa46ecd9658d706cc4`
- upstream revision: [`c80135339bcff5f7f8c2c2380329dfc155b26232`](https://github.com/arkon/FlexibleAdapter/tree/c80135339bcff5f7f8c2c2380329dfc155b26232)

The publication POM is reconstructed from that revision's root and module `build.gradle`:
packaging is AAR, its sole declared dependency is RecyclerView 1.1.0, and the license is
Apache-2.0. The application already directly selects RecyclerView 1.4.0; its selection
remains unchanged. The upstream LICENSE accompanies the artifact, and the existing
application license metadata remains in use.

Settings restrict this repository to the original module and verify AAR/POM SHA-256
before dependency resolution. Corrupt or missing files fail configuration, without a
fallback to a different binary. The library coordinate and consumer declaration are
unchanged. Any future version change must replace the pinned publication, validate its
origin and license, and update the hashes together.
