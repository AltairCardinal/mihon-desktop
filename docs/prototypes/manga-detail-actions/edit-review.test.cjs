// Verify the Sort slot against the neighboring actions in the rendered rail.
module.exports = async function verifySortSlot(sort) {
  const assert = require('node:assert/strict');
  const style = await sort.evaluate(el => {
    const peer = el.previousElementSibling;
    const slot = el.getBoundingClientRect();
    const other = peer.getBoundingClientRect();
    const divider = getComputedStyle(el, '::before');
    return {
      width:slot.width, otherWidth:other.width,
      height:slot.height, otherHeight:other.height,
      dividerWidth:divider.width, dividerHeight:parseFloat(divider.height),
      color:getComputedStyle(el).color, peerColor:getComputedStyle(peer).color,
      labelFits:el.querySelector('span').scrollWidth <= el.clientWidth
    };
  });
  assert.ok(Math.abs(style.width - style.otherWidth) < 2);
  assert.equal(style.height, style.otherHeight);
  assert.equal(style.dividerWidth, '1px');
  assert.ok(style.dividerHeight > 0);
  assert.equal(style.color, style.peerColor);
  assert.ok(style.labelFits);
};
