const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test("书架多选采用 Mihon 顶栏、底部批量菜单和原版选择外观", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 820 } });
    await page.goto(
      pathToFileURL(path.join(__dirname, "device.html")).href +
        "?platform=windows",
    );

    await page.getByTestId("manga-A").click({ modifiers: ["Control"] });
    const iconName = (id) =>
      page.getByTestId(id).locator("svg").getAttribute("data-mihon-icon");

    assert.equal(await page.locator(".library-action-mode").count(), 1);
    assert.equal(
      (await page.getByTestId("selection-count").textContent()).trim(),
      "1",
    );
    assert.equal(await iconName("select-close"), "Outlined.Close");
    assert.equal(await iconName("select-all"), "Outlined.SelectAll");
    assert.equal(await iconName("select-invert"), "Outlined.FlipToBack");
    assert.equal(await page.getByTestId("search-open").count(), 0);
    assert.deepEqual(
      await page
        .locator(".library-batch-actions .batch-action")
        .evaluateAll((buttons) => buttons.map((button) => button.dataset.testid)),
      [
        "batch-category",
        "batch-read",
        "batch-unread",
        "batch-download",
        "batch-overflow",
      ],
    );
    for (const [id, source] of [
      ["batch-category", "AutoMirrored.Outlined.Label"],
      ["batch-read", "Outlined.DoneAll"],
      ["batch-unread", "Outlined.RemoveDone"],
      ["batch-download", "Outlined.Download"],
      ["batch-overflow", "Outlined.MoreVert"],
    ]) {
      assert.equal(await iconName(id), source);
    }
    assert.equal(
      await page.getByTestId("batch-category").getAttribute("aria-label"),
      "设置分类",
    );
    assert.equal(
      await page.locator(".book.selected .cover").evaluate((cover) =>
        getComputedStyle(cover).opacity,
      ),
      "0.76",
    );
    assert.equal(
      await page.getByTestId("batch-read").evaluate((button) =>
        button.compareDocumentPosition(
          document.querySelector('[data-testid="nav-library"]'),
        ) & Node.DOCUMENT_POSITION_FOLLOWING,
      ),
      4,
    );

    const readButton = page.getByTestId("batch-read");
    const readBox = await readButton.boundingBox();
    await page.mouse.move(readBox.x + readBox.width / 2, readBox.y + readBox.height / 2);
    await page.mouse.down();
    await page.waitForTimeout(550);
    assert.equal(await readButton.locator(".batch-action-label").isVisible(), true);
    await page.mouse.up();
    assert.equal(await page.getByTestId("selection-count").textContent(), "1");
    assert.equal(await page.getByTestId("unread-A").textContent(), "2");

    await page.getByTestId("batch-overflow").click();
    await page.keyboard.press("Escape");
    assert.equal(await page.getByTestId("batch-overflow-menu").count(), 0);
    assert.equal(await page.getByTestId("selection-count").textContent(), "1");
    assert.equal(
      await page.getByTestId("batch-overflow").evaluate((button) =>
        button === document.activeElement,
      ),
      true,
    );
    await page.getByTestId("batch-overflow").click();
    assert.deepEqual(
      await page
        .locator('[data-testid="batch-overflow-menu"] button')
        .evaluateAll((buttons) => buttons.map((button) => button.dataset.testid)),
      ["batch-migrate", "batch-delete"],
    );
    await page.getByTestId("batch-migrate").click();
    assert.equal(await page.getByTestId("batch-overflow-menu").count(), 0);
    assert.equal(await page.getByTestId("batch-migrate").count(), 0);
    assert.equal(await page.locator(".library-action-mode").count(), 0);
    await page.keyboard.press("Escape");
    assert.equal(await page.locator(".sheet").count(), 0);
    assert.equal(await page.locator(".library-action-mode").count(), 0);
    await page.getByTestId("manga-A").click({ modifiers: ["Control"] });
    await page.getByTestId("batch-overflow").click();
    await page.getByTestId("batch-delete").click();
    assert.equal(await page.getByTestId("batch-overflow-menu").count(), 0);
    assert.equal(await page.getByTestId("batch-delete-dialog").count(), 1);
    assert.equal(
      await page.locator(".sheet.action-dialog").getAttribute("aria-label"),
      "删除",
    );
    assert.equal(await page.getByTestId("batch-delete-library").isChecked(), false);
    assert.equal(await page.getByTestId("batch-delete-files").isChecked(), false);
    assert.equal(await page.getByTestId("batch-delete-confirm").isDisabled(), true);
    await page.getByTestId("batch-delete-files").check();
    assert.equal(await page.getByTestId("batch-delete-confirm").isDisabled(), false);
    await page.getByTestId("batch-delete-cancel").click();
    assert.equal(await page.getByTestId("selection-count").textContent(), "1");
    assert.equal(
      await page.evaluate(() => window.demo.state.books[0].favorite),
      true,
    );

    await page.getByTestId("batch-category").click();
    assert.equal(await page.getByTestId("batch-category-1").count(), 1);
    assert.equal(
      await page.locator(".sheet.action-dialog").getAttribute("aria-label"),
      "设置分类",
    );
    assert.equal(
      await page.getByTestId("batch-category-1").getAttribute("aria-checked"),
      "true",
    );
    assert.equal(
      await page.getByTestId("batch-category-2").getAttribute("aria-checked"),
      "false",
    );
    const categoryDialog = await page.locator(".sheet.action-dialog").boundingBox();
    const editButton = await page.getByTestId("batch-category-edit").boundingBox();
    const cancelCategoryButton = await page
      .getByTestId("batch-category-cancel")
      .boundingBox();
    assert.ok(editButton.x + editButton.width < categoryDialog.x + categoryDialog.width / 2);
    assert.ok(cancelCategoryButton.x > categoryDialog.x + categoryDialog.width / 2);
    await page.getByTestId("batch-category-edit").click();
    assert.equal(await page.getByTestId("category-page").count(), 1);
    await page.getByTestId("category-back").click();
    assert.equal(await page.locator(".library-action-mode").count(), 0);
    assert.equal(
      await page.getByTestId("nav-library").evaluate((button) =>
        button === document.activeElement,
      ),
      true,
    );
    await page.getByTestId("manga-A").click({ modifiers: ["Control"] });
    await page.getByTestId("batch-category").click();
    await page.getByTestId("batch-category-2").click();
    await page.getByTestId("batch-category-cancel").click();
    assert.equal(await page.getByTestId("selection-count").textContent(), "1");
    assert.equal(
      await page.getByTestId("batch-category").evaluate((button) =>
        button === document.activeElement,
      ),
      true,
    );
    assert.deepEqual(await page.evaluate(() => window.demo.state.books[0].categories), [1]);
    await page.getByTestId("batch-category").click();
    await page.getByTestId("batch-category-2").click();
    await page.getByTestId("batch-category-confirm").click();
    assert.deepEqual(await page.evaluate(() => window.demo.state.books[0].categories), [1, 2]);
    assert.equal(await page.locator(".library-action-mode").count(), 0);

    await page.getByTestId("manga-A").click({ modifiers: ["Control"] });
    await page.getByTestId("batch-overflow").click();
    await page.getByTestId("batch-delete").click();
    await page.getByTestId("batch-delete-files").check();
    await page.getByTestId("batch-delete-confirm").click();
    assert.equal(await page.locator(".library-action-mode").count(), 0);
    assert.equal(await page.evaluate(() => window.demo.state.books[0].favorite), true);
    assert.equal(
      await page.evaluate(() => window.demo.state.books[0].chapters.every((chapter) => !chapter.download && !chapter.downloadStatus)),
      true,
    );

    await page.getByTestId("manga-A").click({ modifiers: ["Control"] });
    await page.getByTestId("batch-download").click();
    assert.deepEqual(
      await page
        .locator('[data-testid="batch-download-menu"] button')
        .evaluateAll((buttons) => buttons.map((button) => button.dataset.testid)),
      [
        "batch-download-1",
        "batch-download-5",
        "batch-download-10",
        "batch-download-25",
        "batch-download-unread",
        "batch-download-bookmarked",
      ],
    );
    await page.getByTestId("batch-download-unread").click();
    assert.equal(await page.locator(".library-action-mode").count(), 0);
    assert.equal(
      await page.evaluate(
        () => window.demo.state.books[0].chapters[2].downloadStatus,
      ),
      "queued",
    );

    await page.getByTestId("manga-A").click({ modifiers: ["Control"] });
    await page.getByTestId("batch-read").click();
    assert.ok(await page.getByTestId("unread-A").count() === 0);
    assert.equal(await page.locator(".library-action-mode").count(), 0);

    for (const layout of ["compact", "comfortable", "cover-only", "list"]) {
      await page.getByTestId("panel-open").click();
      await page.getByTestId("panel-tab-display").click();
      await page.getByTestId(`layout-${layout}`).click();
      await page.keyboard.press("Escape");
      await page.getByTestId("manga-A").click({ modifiers: ["Control"] });
      const selectedBackground = await page
        .locator(".book.selected")
        .evaluate((book) => getComputedStyle(book).backgroundColor);
      assert.equal(
        layout === "list"
          ? selectedBackground !== "rgba(0, 0, 0, 0)"
          : selectedBackground === "rgb(176, 198, 255)",
        true,
      );
      assert.equal(
        await page.locator(".book.selected .cover").evaluate((cover) =>
          getComputedStyle(cover).opacity,
        ),
        layout === "list" ? "1" : "0.76",
      );
      await page.getByTestId("select-close").click();
    }

    await page.getByTestId("manga-A").click({ modifiers: ["Control"] });
    await page.getByTestId("category-2").click();
    await page.getByTestId("manga-F").click();
    assert.equal(await page.getByTestId("selection-count").textContent(), "2");
    await page.getByTestId("batch-category").click();
    assert.equal(
      await page.getByTestId("batch-category-1").getAttribute("aria-checked"),
      "mixed",
    );
    assert.equal(
      await page.getByTestId("batch-category-2").getAttribute("aria-checked"),
      "true",
    );
    await page.getByTestId("batch-category-1").click();
    assert.equal(
      await page.getByTestId("batch-category-1").getAttribute("aria-checked"),
      "false",
    );
    await page.getByTestId("batch-category-1").click();
    assert.equal(
      await page.getByTestId("batch-category-1").getAttribute("aria-checked"),
      "true",
    );
    await page.getByTestId("batch-category-1").click();
    assert.equal(
      await page.getByTestId("batch-category-1").getAttribute("aria-checked"),
      "mixed",
    );
    await page.keyboard.press("Escape");
    assert.equal(await page.getByTestId("selection-count").textContent(), "2");
    assert.equal(
      await page.getByTestId("batch-category").evaluate((button) =>
        button === document.activeElement,
      ),
      true,
    );
    await page.getByTestId("batch-category").click();
    await page.getByTestId("batch-category-confirm").click();
    assert.deepEqual(
      await page.evaluate(() => window.demo.state.books.filter((book) => ["A", "F"].includes(book.id)).map((book) => [book.id, book.categories])),
      [["A", [1, 2]], ["F", [2]]],
    );
    assert.equal(await page.locator(".library-action-mode").count(), 0);

    await page.getByTestId("manga-A").click({ modifiers: ["Control"] });
    await page.getByTestId("batch-overflow").click();
    await page.getByTestId("batch-delete").click();
    await page.getByTestId("batch-delete-library").check();
    await page.getByTestId("batch-delete-files").check();
    await page.getByTestId("batch-delete-confirm").click();
    assert.equal(await page.evaluate(() => window.demo.state.books[0].favorite), false);
    assert.equal(await page.getByTestId("manga-A").count(), 0);
    assert.equal(
      await page.evaluate(() => window.demo.state.books[0].chapters.every((chapter) => !chapter.download && !chapter.downloadStatus)),
      true,
    );
    assert.match(
      await page.getByTestId("notice").textContent(),
      /移出书架并删除已下载章节/,
    );
    assert.equal(await page.locator(".library-action-mode").count(), 0);

    await page.evaluate(() => window.demo.scenario("smart-samples"));
    await page.getByTestId("manga-B").click({ modifiers: ["Control"] });
    assert.deepEqual(
      await page
        .locator(".library-batch-actions .batch-action")
        .evaluateAll((buttons) => buttons.map((button) => button.dataset.testid)),
      [
        "batch-category",
        "batch-read",
        "batch-unread",
        "batch-migrate",
        "batch-delete",
      ],
    );
    assert.equal(await page.getByTestId("batch-download").count(), 0);
    assert.equal(await page.getByTestId("batch-overflow").count(), 0);
  } finally {
    await browser.close();
  }
});

test("Android 窄屏长按后的书架多选弹层保持 Mihon 布局", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 320, height: 790 } });
    await page.goto(
      pathToFileURL(path.join(__dirname, "device.html")).href +
        "?platform=android",
    );

    const manga = page.getByTestId("manga-A");
    const box = await manga.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.waitForTimeout(550);
    await page.mouse.up();

    assert.equal(await page.locator(".library-action-mode").count(), 1);
    assert.deepEqual(
      await page
        .locator(".library-batch-actions .batch-action")
        .evaluateAll((buttons) => buttons.map((button) => button.dataset.testid)),
      [
        "batch-category",
        "batch-read",
        "batch-unread",
        "batch-download",
        "batch-overflow",
      ],
    );

    await page.getByTestId("batch-category").click();
    const categoryDialog = await page
      .locator(".sheet.action-dialog")
      .boundingBox();
    assert.ok(categoryDialog.x >= 0);
    assert.ok(categoryDialog.x + categoryDialog.width <= 320);
    assert.ok(categoryDialog.y >= 0);
    assert.ok(categoryDialog.y + categoryDialog.height <= 790);
    assert.equal(
      await page.getByTestId("batch-category-1").getAttribute("aria-checked"),
      "true",
    );
    await page.getByTestId("batch-category-cancel").click();
    assert.equal(await page.getByTestId("selection-count").textContent(), "1");

    await page.getByTestId("batch-overflow").click();
    await page.getByTestId("batch-delete").click();
    const deleteDialog = await page
      .locator(".sheet.action-dialog")
      .boundingBox();
    assert.ok(deleteDialog.x >= 0);
    assert.ok(deleteDialog.x + deleteDialog.width <= 320);
    assert.ok(deleteDialog.y >= 0);
    assert.ok(deleteDialog.y + deleteDialog.height <= 790);
    assert.equal(await page.getByTestId("batch-delete-confirm").isDisabled(), true);
    await page.keyboard.press("Escape");
    assert.equal(await page.locator(".sheet.action-dialog").count(), 0);
    assert.equal(await page.getByTestId("selection-count").textContent(), "1");
  } finally {
    await browser.close();
  }
});
