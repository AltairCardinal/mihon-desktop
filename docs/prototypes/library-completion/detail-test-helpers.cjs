// Exercise the visible cover-viewer route rather than dispatching application actions.
async function openCoverMenu(page) {
  if (!(await page.getByTestId("detail-cover-viewer").count())) {
    await page.getByTestId("detail-cover-open").click();
  }
  await page.getByTestId("detail-cover-menu").click();
}
async function openMoreSettings(page) {
  if (await page.getByRole("dialog", { name: "设置" }).count()) return;
  if (!(await page.getByTestId("settings-open").isVisible())) {
    await page.getByTestId("nav-more").click();
  }
  await page.getByTestId("settings-open").click();
}
async function openLibraryMenuAction(page, action) {
  if (!(await page.getByTestId(action).count())) {
    await page.getByTestId("more-open").click();
  }
  await page.getByTestId(action).click();
}
module.exports = { openCoverMenu, openMoreSettings, openLibraryMenuAction };
