#!/usr/bin/env node
// Structural validation of ARTracker replay chunks WITHOUT a browser.
//
//   node tools/replay-viewer/validate-chunks.mjs <chunks.json> [--forbid <text>]...
//
// Input: a JSON array of replay_chunk attributes ({seq,start,end,full_snapshot,events}) or of backend items whose
// `attributes` hold them (same formats as index.html). Chunks are sorted by start then seq and their events are
// concatenated, exactly like the viewer. Checks, replaying the rrweb node tree event by event:
//   - every event has a numeric `type` and `timestamp`; the first events are Meta (4) then FullSnapshot (2)
//   - node ids are unique within a snapshot; elements have a tagName, string attribute values, childNodes arrays
//   - mutation removes/adds/texts/attributes point at nodes that exist (parentId, nextId, text node ids)
//   - mouse/touch events point at existing nodes and carry numeric coordinates
//   - masked nodes (data-artracker-masked) contain only `*` text
//   - none of the --forbid strings appears anywhere in the events (e.g. a password typed in the app)
// Exit code 0 = valid, 1 = problems found (listed), 2 = usage / unreadable input.

import { readFileSync } from 'node:fs';

const EVENT_FULL_SNAPSHOT = 2;
const EVENT_INCREMENTAL = 3;
const EVENT_META = 4;
const NODE_DOCUMENT = 0;
const NODE_DOCTYPE = 1;
const NODE_ELEMENT = 2;
const NODE_TEXT = 3;
const SOURCE_MUTATION = 0;
const SOURCE_MOUSE_INTERACTION = 2;
const SOURCE_TOUCH_MOVE = 6;

const MAX_REPORTED = 40;

/** Accepts an array (or the backend's {items: [...]}) of chunk attributes or of backend items; returns [{session, attrs}]. */
export function toChunks(parsed) {
  if (parsed && !Array.isArray(parsed) && Array.isArray(parsed.items)) parsed = parsed.items;
  if (!Array.isArray(parsed)) throw new Error('the file must contain a JSON array (or an object with an "items" array)');
  const chunks = [];
  for (const entry of parsed) {
    const isItem = entry && typeof entry === 'object' && !Array.isArray(entry.events) && entry.attributes !== undefined;
    if (isItem && entry.name !== 'replay_chunk') continue;
    let attrs = isItem ? entry.attributes : entry;
    if (typeof attrs === 'string') {
      try { attrs = JSON.parse(attrs); } catch { attrs = null; }
    }
    if (attrs && Array.isArray(attrs.events)) chunks.push({ session: isItem && entry.session_id ? String(entry.session_id) : null, attrs });
  }
  if (chunks.length === 0) throw new Error('no replay chunks found');
  return chunks;
}

/** One recording per session: a backend export mixes sessions. Returns Map(session -> attrs[]). */
export function groupBySession(chunks) {
  const groups = new Map();
  for (const { session, attrs } of chunks) {
    const key = session ?? '(single recording)';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(attrs);
  }
  return groups;
}

const num = (v, fallback) => (typeof v === 'number' && Number.isFinite(v) ? v : fallback);

export function mergeEvents(chunks) {
  const sorted = [...chunks].sort((a, b) => num(a.start, 0) - num(b.start, 0) || num(a.seq, 0) - num(b.seq, 0));
  return sorted.flatMap((c) => c.events);
}

export function validate(events, { forbid = [] } = {}) {
  const problems = [];
  const warnings = [];
  const stats = { events: events.length, snapshots: 0, mutations: 0, touches: 0, maxNodes: 0 };
  const problem = (i, msg) => problems.push(`event #${i}: ${msg}`);

  // id -> { type, parentId, tagName, masked, children:Set<id> }
  let nodes = new Map();
  let haveSnapshot = false;

  const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

  function removeSubtree(id) {
    const n = nodes.get(id);
    if (!n) return;
    for (const c of [...n.children]) removeSubtree(c);
    nodes.get(n.parentId)?.children.delete(id);
    nodes.delete(id);
  }

  function checkAttributes(i, attrs, where, allowNull) {
    if (!isObj(attrs)) { problem(i, `${where}: attributes must be an object`); return; }
    for (const [k, v] of Object.entries(attrs)) {
      if (typeof v === 'string') continue;
      if (allowNull && v === null) continue;
      problem(i, `${where}: attribute "${k}" has a non-string value (${JSON.stringify(v)})`);
    }
  }

  // Registers `node` (and its subtree) under `parentId`; returns false when the shape is unusable.
  function addNode(i, node, parentId, where) {
    if (!isObj(node)) { problem(i, `${where}: node is not an object`); return false; }
    if (!Number.isInteger(node.id)) { problem(i, `${where}: node without an integer id`); return false; }
    if (nodes.has(node.id)) { problem(i, `${where}: duplicate node id ${node.id}`); return false; }
    const entry = { type: node.type, parentId, tagName: node.tagName, masked: false, children: new Set() };
    nodes.set(node.id, entry);
    if (parentId !== null) nodes.get(parentId)?.children.add(node.id);
    switch (node.type) {
      case NODE_DOCUMENT:
        break;
      case NODE_DOCTYPE:
        break;
      case NODE_ELEMENT:
        if (typeof node.tagName !== 'string' || node.tagName === '') problem(i, `${where}: element ${node.id} without tagName`);
        checkAttributes(i, node.attributes, `${where}: element ${node.id}`, false);
        entry.masked = isObj(node.attributes) && node.attributes['data-artracker-masked'] === 'true';
        break;
      case NODE_TEXT:
        if (typeof node.textContent !== 'string') problem(i, `${where}: text node ${node.id} without textContent`);
        else if (parentId !== null && nodes.get(parentId)?.masked && !/^\*+$/.test(node.textContent)) {
          problem(i, `${where}: masked element ${parentId} holds unmasked text`);
        }
        break;
      default:
        problem(i, `${where}: node ${node.id} has unknown type ${node.type}`);
        return false;
    }
    if (node.type === NODE_DOCUMENT || node.type === NODE_ELEMENT) {
      if (!Array.isArray(node.childNodes)) { problem(i, `${where}: node ${node.id} without childNodes array`); return true; }
      for (const child of node.childNodes) addNode(i, child, node.id, where);
    } else if (node.childNodes !== undefined && (!Array.isArray(node.childNodes) || node.childNodes.length > 0)) {
      problem(i, `${where}: leaf node ${node.id} has children`);
    }
    return true;
  }

  function mutation(i, d) {
    stats.mutations++;
    for (const key of ['texts', 'attributes', 'removes', 'adds']) {
      if (!Array.isArray(d[key])) problem(i, `mutation.${key} must be an array`);
    }
    // rrweb applies removes, then adds, then texts and attributes.
    for (const r of d.removes ?? []) {
      const n = nodes.get(r.id);
      if (!n) { problem(i, `remove of unknown node ${r.id}`); continue; }
      if (n.parentId !== r.parentId) problem(i, `remove of node ${r.id} names parent ${r.parentId}, actual parent ${n.parentId}`);
      removeSubtree(r.id);
    }
    for (const a of d.adds ?? []) {
      if (!nodes.has(a.parentId)) { problem(i, `add under unknown parent ${a.parentId}`); continue; }
      if (a.nextId !== null && a.nextId !== undefined) {
        const next = nodes.get(a.nextId);
        if (!next) problem(i, `add references unknown nextId ${a.nextId}`);
        else if (next.parentId !== a.parentId) problem(i, `add nextId ${a.nextId} is not a child of parent ${a.parentId}`);
      }
      addNode(i, a.node, a.parentId, 'add');
    }
    for (const t of d.texts ?? []) {
      const n = nodes.get(t.id);
      if (!n) problem(i, `text change targets unknown node ${t.id}`);
      else if (n.type !== NODE_TEXT) problem(i, `text change targets node ${t.id}, which is not a text node (type ${n.type})`);
      else if (typeof t.value !== 'string') problem(i, `text change of node ${t.id} has a non-string value`);
      else if (nodes.get(n.parentId)?.masked && !/^\*+$/.test(t.value)) problem(i, `text change unmasks masked element ${n.parentId}`);
    }
    for (const a of d.attributes ?? []) {
      const n = nodes.get(a.id);
      if (!n) problem(i, `attribute change targets unknown node ${a.id}`);
      else if (n.type !== NODE_ELEMENT) problem(i, `attribute change targets node ${a.id}, which is not an element`);
      else checkAttributes(i, a.attributes, `attribute change of node ${a.id}`, true);
    }
  }

  events.forEach((e, i) => {
    if (!isObj(e)) { problem(i, 'not an object'); return; }
    if (typeof e.type !== 'number') problem(i, 'missing numeric type');
    if (typeof e.timestamp !== 'number' || !Number.isFinite(e.timestamp)) problem(i, 'missing numeric timestamp');
    if (i > 0 && typeof e.timestamp === 'number' && typeof events[i - 1]?.timestamp === 'number' && e.timestamp < events[i - 1].timestamp) {
      warnings.push(`event #${i}: timestamp goes back by ${events[i - 1].timestamp - e.timestamp} ms (rrweb sorts by timestamp, so this is harmless)`);
    }

    if (e.type === EVENT_META) {
      if (!isObj(e.data) || typeof e.data.href !== 'string' || !(e.data.width > 0) || !(e.data.height > 0)) {
        problem(i, 'Meta needs data.href (string) and positive data.width/height');
      }
    } else if (e.type === EVENT_FULL_SNAPSHOT) {
      stats.snapshots++;
      nodes = new Map();
      haveSnapshot = true;
      if (!isObj(e.data) || !isObj(e.data.node)) { problem(i, 'FullSnapshot without data.node'); return; }
      if (e.data.node.type !== NODE_DOCUMENT) problem(i, 'FullSnapshot root must be a Document node');
      addNode(i, e.data.node, null, 'snapshot');
      stats.maxNodes = Math.max(stats.maxNodes, nodes.size);
    } else if (e.type === EVENT_INCREMENTAL) {
      if (!haveSnapshot) { problem(i, 'incremental event before any FullSnapshot'); return; }
      const d = e.data;
      if (!isObj(d)) { problem(i, 'incremental event without data'); return; }
      if (d.source === SOURCE_MUTATION) {
        mutation(i, d);
        stats.maxNodes = Math.max(stats.maxNodes, nodes.size);
      } else if (d.source === SOURCE_MOUSE_INTERACTION) {
        stats.touches++;
        if (!nodes.has(d.id)) problem(i, `interaction targets unknown node ${d.id}`);
        if (typeof d.x !== 'number' || typeof d.y !== 'number') problem(i, 'interaction without numeric x/y');
      } else if (d.source === SOURCE_TOUCH_MOVE) {
        stats.touches++;
        if (!Array.isArray(d.positions) || d.positions.length === 0) problem(i, 'touch move without positions');
        for (const p of d.positions ?? []) {
          if (!nodes.has(p.id)) problem(i, `touch move targets unknown node ${p.id}`);
          if (typeof p.x !== 'number' || typeof p.y !== 'number') problem(i, 'touch move position without numeric x/y');
        }
      } else {
        problem(i, `unsupported incremental source ${d.source}`);
      }
    } else if (typeof e.type === 'number') {
      problem(i, `unexpected event type ${e.type}`);
    }
  });

  if (events.length < 2 || events[0]?.type !== EVENT_META || events[1]?.type !== EVENT_FULL_SNAPSHOT) {
    problems.unshift('the first two events must be Meta (4) then FullSnapshot (2)');
  }

  const text = JSON.stringify(events);
  for (const needle of forbid) {
    if (needle && text.includes(needle)) problems.push(`forbidden text "${needle}" appears in the recording`);
  }
  return { problems, warnings, stats };
}

function main(argv) {
  const files = [];
  const forbid = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--forbid') forbid.push(argv[++i] ?? '');
    else files.push(argv[i]);
  }
  if (files.length !== 1) {
    console.error('usage: node validate-chunks.mjs <chunks.json> [--forbid <text>]...');
    return 2;
  }
  let groups;
  try {
    groups = groupBySession(toChunks(JSON.parse(readFileSync(files[0], 'utf8'))));
  } catch (err) {
    console.error(`cannot read ${files[0]}: ${err.message}`);
    return 2;
  }
  let failed = 0;
  for (const [session, chunks] of groups) {
    const { problems, warnings, stats } = validate(mergeEvents(chunks), { forbid });
    const prefix = groups.size > 1 ? `[session ${session}] ` : '';
    console.log(
      `${prefix}${chunks.length} chunks, ${stats.events} events: ${stats.snapshots} full snapshots, ${stats.mutations} mutations, ` +
        `${stats.touches} touch events, max ${stats.maxNodes} nodes`,
    );
    for (const w of warnings.slice(0, 5)) console.log(`${prefix}warning: ${w}`);
    if (warnings.length > 5) console.log(`${prefix}warning: ... ${warnings.length - 5} more timestamp warnings`);
    if (problems.length === 0) {
      console.log(`${prefix}OK: structure is valid`);
      continue;
    }
    failed += problems.length;
    for (const p of problems.slice(0, MAX_REPORTED)) console.error(`${prefix}PROBLEM: ${p}`);
    if (problems.length > MAX_REPORTED) console.error(`${prefix}... and ${problems.length - MAX_REPORTED} more`);
  }
  if (failed > 0) {
    console.error(`FAILED: ${failed} problem(s)`);
    return 1;
  }
  return 0;
}

import { fileURLToPath } from 'node:url';
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) process.exit(main(process.argv.slice(2)));
