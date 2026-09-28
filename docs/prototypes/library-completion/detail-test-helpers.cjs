// Exercise the visible cover-viewer route rather than dispatching application actions.
async function openCoverMenu(page) {
  if (!(await page.getByTestId("detail-cover-viewer").count())) {
    await page.getByTestId("detail-cover-open").click();
  }
  await page.getByTestId("detail-cover-menu").click();
}
module.exports = { openCoverMenu };
