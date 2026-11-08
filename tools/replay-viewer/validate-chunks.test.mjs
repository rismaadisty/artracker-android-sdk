// Self-test of the validator: node --test tools/replay-viewer
import assert from 'node:assert/strict';
import test from 'node:test';
import { groupBySession, mergeEvents, toChunks, validate } from './validate-chunks.mjs';

const el = (id, children = [], attributes = {}) => ({ type: 2, tagName: 'div', attributes, childNodes: children, id });
const text = (id, textContent) => ({ type: 3, textContent, id });
const snapshot = (ts, body) => ({
  type: 2,
  timestamp: ts,
  data: {
    node: {
      type: 0,
      id: 1,
      childNodes: [{ type: 1, name: 'html', publicId: '', systemId: '', id: 5 }, el(2, [el(3), body])],
    },
  },
});
const meta = (ts) => ({ type: 4, timestamp: ts, data: { href: 'android://A', width: 360, height: 640 } });
const mutation = (ts, data) => ({
  type: 3,
  timestamp: ts,
  data: { source: 0, texts: [], attributes: [], removes: [], adds: [], ...data },
});
const good = () => [meta(1), snapshot(1, el(4, [el(10, [text(11, 'hello')])]))];
const problemsOf = (events, opts) => validate(events, opts).problems;

test('a well formed recording is valid', () => {
  const events = [
    ...good(),
    mutation(2, { texts: [{ id: 11, value: 'bye' }], attributes: [{ id: 10, attributes: { style: 'a', gone: null } }] }),
    mutation(3, { adds: [{ parentId: 10, nextId: null, node: el(12) }] }),
    mutation(4, { removes: [{ parentId: 10, id: 12 }] }),
    { type: 3, timestamp: 5, data: { source: 2, type: 7, id: 4, x: 1, y: 2 } },
  ];
  assert.deepEqual(problemsOf(events), []);
});

test('detects structural problems', () => {
  const cases = {
    'duplicate node id': [meta(1), snapshot(1, el(4, [el(10), el(10)]))],
    'non-string attribute value': [meta(1), snapshot(1, el(4, [el(10, [], { width: 5 })]))],
    'missing timestamp': [{ type: 4, data: { href: 'x', width: 1, height: 1 } }, snapshot(1, el(4))],
    'first events are not Meta+FullSnapshot': [snapshot(1, el(4)), meta(1)],
    'add under unknown parent': [...good(), mutation(2, { adds: [{ parentId: 999, nextId: null, node: el(12) }] })],
    'add with a nextId that does not exist': [...good(), mutation(2, { adds: [{ parentId: 10, nextId: 77, node: el(12) }] })],
    'text change on an element': [...good(), mutation(2, { texts: [{ id: 10, value: 'x' }] })],
    'text change on a missing node': [...good(), mutation(2, { texts: [{ id: 404, value: 'x' }] })],
    'attribute change on a missing node': [...good(), mutation(2, { attributes: [{ id: 404, attributes: { a: 'b' } }] })],
    'remove of an unknown node': [...good(), mutation(2, { removes: [{ parentId: 10, id: 404 }] })],
    'interaction on a missing node': [...good(), { type: 3, timestamp: 2, data: { source: 2, type: 7, id: 404, x: 1, y: 1 } }],
    'incremental before snapshot': [meta(1), mutation(1, {}), snapshot(1, el(4))],
    'unmasked text inside a masked element': [meta(1), snapshot(1, el(4, [el(10, [text(11, 'secret')], { 'data-artracker-masked': 'true' })]))],
  };
  for (const [name, events] of Object.entries(cases)) {
    assert.notEqual(problemsOf(events).length, 0, `should be rejected: ${name}`);
  }
});

test('masked text made of asterisks is fine; --forbid finds leaked text', () => {
  const masked = [meta(1), snapshot(1, el(4, [el(10, [text(11, '*****')], { 'data-artracker-masked': 'true' })]))];
  assert.deepEqual(problemsOf(masked), []);
  assert.equal(problemsOf(good(), { forbid: ['hello'] }).length, 1);
});

test('removed subtrees free their ids for reuse in a later snapshot, but not within one', () => {
  const events = [
    ...good(),
    mutation(2, { removes: [{ parentId: 4, id: 10 }] }),
    mutation(3, { adds: [{ parentId: 4, nextId: null, node: el(10, [text(11, 'again')]) }] }),
  ];
  assert.deepEqual(problemsOf(events), []);
});

test('chunks are sorted by start then seq and grouped by session; backend {items} shape is accepted', () => {
  const chunk = (seq, start, events) => ({ seq, start, end: start, events });
  const items = {
    items: [
      { kind: 'session', name: 'replay_chunk', session_id: 'B', attributes: chunk(0, 5, [meta(5)]) },
      { kind: 'session', name: 'replay_chunk', session_id: 'A', attributes: chunk(1, 10, [{ type: 3, timestamp: 10 }]) },
      { kind: 'event', name: 'login', session_id: 'A', attributes: {} },
      { kind: 'session', name: 'replay_chunk', session_id: 'A', attributes: chunk(0, 10, [meta(10)]) },
    ],
  };
  const groups = groupBySession(toChunks(items));
  assert.deepEqual([...groups.keys()].sort(), ['A', 'B']);
  assert.deepEqual(mergeEvents(groups.get('A')).map((e) => e.type), [4, 3]);
  assert.throws(() => toChunks([{ kind: 'event', name: 'x', attributes: {} }]), /no replay chunks/);
});
