---
layout: home
title: Read further
hero:
  name: Mihon Desktop
  text: More room for what you read.
  tagline: Read on your desktop. Continue on Android. Discover more from the authors you follow.
  image:
    src: /mihon-desktop.png
    alt: Mihon Desktop project icon
  actions:
    - theme: brand
      text: Explore the features
      link: /features
    - theme: alt
      text: Build a preview
      link: /getting-started
features:
  - title: A wider reading experience
    details: A Windows-first desktop app, with side-by-side pages and image-edge analysis to help pair spreads.
    link: /features#two-page-reading
    linkText: Explore reading
  - title: A library that travels
    details: Sync favorites, followed authors, read status, and reading position between compatible builds of this fork.
    link: /features#multi-device-sync
    linkText: Understand sync
  - title: Follow the people behind the pages
    details: Follow authors, explore their works across configured sources, and improve the language information in your archive.
    link: /features#authors-and-discovery
    linkText: Discover the author archive
---

<div class="home-body">
  <div class="home-status">
    <strong>Early development</strong>
    <span>No public release yet. Explore the source, build a preview, and help shape what comes next.</span>
    <a href="./getting-started.html">Get started →</a>
  </div>
  <div class="home-story">
    <div>
      <h2>A familiar foundation.<br>More ambitious possibilities.</h2>
      <p>Mihon Desktop is an independently maintained fork of Mihon. We’re extending its reading experience with a desktop home, smarter two-page layouts, multi-device continuity, author discovery, and language-aware organization.</p>
      <p>Windows is the primary desktop target. The repository also maintains macOS builds and its own Android app, with shared Kotlin logic connecting parts of the experience.</p>
      <a href="./features.html">Read the current feature guide →</a>
    </div>
    <div class="home-principles">
      <h3>Clear about what works today</h3>
      <p>Desktop can assist with spread matching; Android currently offers manual pairing. Language information uses metadata, not image recognition. Each feature has a stated boundary.</p>
      <h3>One current guide, a traceable past</h3>
      <p>The feature guide describes the current public baseline. Accepted changes update it in place; the changelog and each page’s Git history preserve the story.</p>
      <h3>Built with contributors</h3>
      <p>Reproducible reading cases, careful testing, design, code, and translations all help this project grow.</p>
    </div>
  </div>
  <div class="home-bottom">
    <p>Help make the next reading session better.</p>
    <a href="./contributing.html">Find your way to contribute →</a>
  </div>
  <PageHistory />
</div>
