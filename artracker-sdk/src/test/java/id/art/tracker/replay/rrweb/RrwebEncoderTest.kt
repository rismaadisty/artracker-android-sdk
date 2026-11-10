package id.art.tracker.replay.rrweb

import id.art.tracker.core.Json
import id.art.tracker.replay.diff.WireframeDiff
import id.art.tracker.replay.diff.WireframeDiffer
import id.art.tracker.replay.model.ImageFit
import id.art.tracker.replay.model.TextAlignment
import id.art.tracker.replay.model.VisualStyle
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class RrwebEncoderTest {
    private val encoder = RrwebEncoder()

    private fun box(id: Int, x: Int = 0, y: Int = 0, w: Int = 100, h: Int = 50, vararg kids: Wireframe) =
        Wireframe(id, x, y, w, h, WireframeType.CONTAINER, children = kids.toList())

    private fun label(id: Int, text: String, x: Int = 0, y: Int = 0, masked: Boolean = false) =
        Wireframe(id, x, y, 80, 20, WireframeType.TEXT, text = text, masked = masked)

    private fun root(vararg kids: Wireframe) = box(1, 0, 0, 360, 640, *kids)

    private fun Map<String, Any?>.map(key: String) = this[key] as Map<String, Any?>
    private fun Map<String, Any?>.list(key: String) = this[key] as List<Map<String, Any?>>
    private fun Map<String, Any?>.node(): Map<String, Any?> = this

    private fun body(snapshot: Map<String, Any?>): Map<String, Any?> {
        val document = snapshot.map("data").map("node")
        val html = document.list("childNodes").first { it["tagName"] == "html" }
        return html.list("childNodes").first { it["tagName"] == "body" }
    }

    private fun walk(node: Map<String, Any?>, visit: (Map<String, Any?>) -> Unit) {
        visit(node)
        (node["childNodes"] as? List<Map<String, Any?>>)?.forEach { walk(it, visit) }
    }

    private fun allNodes(snapshot: Map<String, Any?>): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        walk(snapshot.map("data").map("node")) { out.add(it) }
        return out
    }

    private fun elementFor(snapshot: Map<String, Any?>, wireframeId: Int): Map<String, Any?> {
        val nodeId = encoder.nodeIdFor(wireframeId)
        return allNodes(snapshot).first { it["type"] == 2 && it["id"] == nodeId }
    }

    private fun firstDiv(snapshot: Map<String, Any?>) =
        allNodes(snapshot).first { it["tagName"] == "div" }

    private fun style(node: Map<String, Any?>) = (node["attributes"] as Map<String, Any?>)["style"] as String

    private fun textsOf(snapshot: Map<String, Any?>): List<String> =
        allNodes(snapshot).filter { it["type"] == 3 && it["id"] != STYLE_TEXT_NODE_ID }.map { it["textContent"] as String }

    @Test
    fun fullSnapshot_headCarriesSelfContainedStylesheet() {
        val snap = encoder.fullSnapshot(box(2, 10, 20, 50, 40), 1234L)
        val html = snap.map("data").map("node").list("childNodes").first { it["tagName"] == "html" }
        val head = html.list("childNodes").first { it["tagName"] == "head" }
        val style = head.list("childNodes").single()
        assertEquals(2, style["type"])
        assertEquals(STYLE_NODE_ID, style["id"])
        val textNode = style.list("childNodes").single()
        assertEquals(3, textNode["type"])
        assertEquals(STYLE_TEXT_NODE_ID, textNode["id"])
        val css = textNode["textContent"] as String
        assertTrue(css, css.contains("font-family:Roboto"))
        assertTrue(css, css.contains("[data-artracker-type=\"TEXT\"]"))
        assertTrue(css, css.contains("white-space:nowrap"))
        assertFalse("the default sheet must not invent fills or borders", css.contains("border:") || css.contains("border-radius") || css.contains("rgba("))
        // Reserved ids stay clear of wireframe nodes (which start at 10).
        assertTrue(allNodes(snap).filter { it !== style && it !== textNode }.none { it["id"] == STYLE_NODE_ID || it["id"] == STYLE_TEXT_NODE_ID })
    }

    @Test
    fun fullSnapshot_structure() {
        val snap = encoder.fullSnapshot(box(2, 10, 20, 50, 40), 1234L)
        assertEquals(2, snap["type"])
        assertEquals(1234L, snap["timestamp"])
        val data = snap.map("data")
        assertEquals(mapOf("left" to 0, "top" to 0), data["initialOffset"])
        val node = data.map("node")
        assertEquals(0, node["type"])
        assertEquals(1, node["id"])
        val html = node.list("childNodes").first { it["tagName"] == "html" }
        assertEquals(2, html["type"])
        assertEquals(2, html["id"])
        val htmlKids = html.list("childNodes")
        assertEquals(listOf("head", "body"), htmlKids.map { it["tagName"] })
        assertEquals(listOf(3, 4), htmlKids.map { it["id"] })
        val headKids = htmlKids[0].list("childNodes")
        assertEquals(listOf("style"), headKids.map { it["tagName"] })
        val bodyStyle = style(htmlKids[1])
        assertTrue(bodyStyle, bodyStyle.contains("margin:0;position:relative;width:50px;height:40px;overflow:hidden"))
        val div = firstDiv(snap)
        assertEquals(10, div["id"])
        assertEquals(2, div["type"])
        assertTrue(style(div), style(div).contains("left:10px"))
        assertTrue(style(div), style(div).contains("top:20px"))
        assertTrue(style(div).contains("width:50px;height:40px;"))
        assertEquals("CONTAINER", (div["attributes"] as Map<*, *>)["data-artracker-type"])
        assertEquals(div, body(snap).list("childNodes").single())
        assertEquals(1, allNodes(snap).count { it["tagName"] == "div" })
    }

    @Test
    fun fullSnapshot_childrenUseParentRelativeCoordinates() {
        val snap = encoder.fullSnapshot(root(box(2, 10, 20, 100, 100, label(3, "t", 15, 27))), 0L)
        val child = elementFor(snap, 3)
        assertTrue(style(child), style(child).contains("left:5px;top:7px;"))
    }

    private fun styleOfNode(w: Wireframe): String = style(elementFor(encoder.fullSnapshot(root(w), 0L), w.id))

    @Test
    fun visual_shapeBorderShadowGradient() {
        val card = Wireframe(
            2, 12, 160, 296, 280, WireframeType.CONTAINER, background = 0xFFFFFFFF.toInt(),
            visual = VisualStyle(
                radiiDp = listOf(12f, 12f, 12f, 12f), borderWidthDp = 1f, borderColor = 0xFFE2E8F0.toInt(),
                shadowDp = 2f, clip = true, gradient = "linear-gradient(180deg,#ffffff,#eeeeee)",
            ),
        )
        val s = styleOfNode(card)
        assertTrue(s, s.contains("border-radius:12px;"))
        assertTrue(s, s.contains("border:1px solid #e2e8f0;"))
        assertTrue(s, s.contains("box-shadow:0px 2px 4px rgba(0,0,0,0.14);"))
        assertTrue(s, s.contains("background-image:linear-gradient(180deg,#ffffff,#eeeeee);"))
        assertTrue(s, s.endsWith("overflow:hidden;"))
    }

    @Test
    fun visual_unevenRadii_useCssCornerOrder() {
        val s = styleOfNode(Wireframe(2, 0, 0, 80, 40, WireframeType.BUTTON, visual = VisualStyle(radiiDp = listOf(20f, 0f, 4.5f, 8f))))
        assertTrue(s, s.contains("border-radius:20px 0px 4.5px 8px;"))
    }

    @Test
    fun visual_textProperties_singleLine() {
        val t = Wireframe(
            2, 0, 0, 100, 22, WireframeType.TEXT, text = "Hi", textSizeSp = 16f, fontWeight = 600,
            visual = VisualStyle(fontFamily = "serif", italic = true, textAlign = TextAlignment.CENTER, lineHeightDp = 22f),
        )
        val s = styleOfNode(t)
        assertTrue(s, s.contains("font-size:16px;font-weight:600;"))
        assertTrue(s, s.contains("font-family:Roboto,serif;font-style:italic;justify-content:center;line-height:22px;"))
        assertFalse(s, s.contains("white-space"))
    }

    @Test
    fun visual_textProperties_multiLineWraps() {
        val t = Wireframe(
            2, 0, 0, 100, 44, WireframeType.TEXT, text = "a b c d",
            visual = VisualStyle(wrap = true, textAlign = TextAlignment.RIGHT),
        )
        val s = styleOfNode(t)
        assertTrue(s, s.contains("display:block;white-space:normal;text-align:right;"))
        assertFalse(s, s.contains("justify-content"))
    }

    @Test
    fun visual_textProperties_ignoredWithoutText() {
        val s = styleOfNode(Wireframe(2, 0, 0, 10, 10, WireframeType.CONTAINER, visual = VisualStyle(italic = true, textAlign = TextAlignment.CENTER)))
        assertFalse(s, s.contains("font-style"))
        assertFalse(s, s.contains("justify-content"))
    }

    @Test
    fun visual_image_isABackgroundImage_andBeatsGradient() {
        val uri = "data:image/webp;base64,UklGRkYAAABXRUJQ+/=="
        val s = styleOfNode(
            Wireframe(2, 0, 0, 24, 24, WireframeType.IMAGE, visual = VisualStyle(image = uri, imageFit = ImageFit.CONTAIN, gradient = "linear-gradient(0deg,#000,#fff)")),
        )
        assertTrue(s, s.contains("background-image:url(\"$uri\");background-size:contain;background-repeat:no-repeat;background-position:center;"))
        assertFalse(s, s.contains("linear-gradient"))
        // An icon replaces the grey placeholder.
        assertFalse(s, s.contains("#cccccc"))
    }

    @Test
    fun visual_imagePadding_keepsTheIconInsideTheContentBox() {
        val uri = "data:image/webp;base64,AAAA"
        val s = styleOfNode(
            Wireframe(2, 0, 0, 48, 48, WireframeType.IMAGE, visual = VisualStyle(image = uri, imageFit = ImageFit.CONTAIN, paddingDp = listOf(8f, 12f, 8f, 12f))),
        )
        assertTrue(s, s.contains("padding:8px 12px 8px 12px;background-origin:content-box;"))
        // Padding only exists to place an icon: without one it is not written.
        val none = styleOfNode(Wireframe(2, 0, 0, 48, 48, WireframeType.IMAGE, visual = VisualStyle(paddingDp = listOf(8f, 8f, 8f, 8f))))
        assertFalse(none, none.contains("padding"))
    }

    @Test
    fun visual_untrustedStrings_areNeverWrittenIntoCss() {
        val evil = Wireframe(
            2, 0, 0, 24, 24, WireframeType.CONTAINER,
            visual = VisualStyle(
                image = "data:image/webp;base64,AAAA\");background:url(http://evil/x",
                gradient = "linear-gradient(0deg,red);background:url(http://evil/x)",
                fontFamily = "x;background:url(http://evil/x)",
            ),
        )
        val s = styleOfNode(evil)
        assertFalse(s, s.contains("evil"))
        assertFalse(s, s.contains("url("))
    }

    @Test
    fun visual_nonFiniteOrNegativeNumbers_areDropped() {
        val s = styleOfNode(
            Wireframe(
                2, 0, 0, 10, 10, WireframeType.CONTAINER,
                visual = VisualStyle(radiiDp = listOf(Float.NaN, -1f, 3f, 3f), borderWidthDp = Float.POSITIVE_INFINITY, borderColor = 0xFF000000.toInt(), shadowDp = -2f),
            ),
        )
        assertFalse(s, s.contains("border-radius"))
        assertFalse(s, s.contains("border:"))
        assertFalse(s, s.contains("box-shadow"))
    }

    @Test
    fun fullSnapshot_styleDetails() {
        val styled = Wireframe(
            2, 1, 2, 30, 40, WireframeType.TEXT, text = "x", textColor = 0xFF102030.toInt(), textSizeSp = 14f,
            background = 0x80FF0000.toInt(), alpha = 0.5f,
        )
        val s = style(elementFor(encoder.fullSnapshot(root(styled), 0L), 2))
        assertEquals(
            "position:absolute;left:1px;top:2px;width:30px;height:40px;" +
                "background-color:rgba(255,0,0,0.502);color:#102030;font-size:14px;opacity:0.5;",
            s,
        )
        encoder.reset()
        val plain = style(elementFor(encoder.fullSnapshot(root(box(2)), 0L), 2))
        assertEquals("position:absolute;left:0px;top:0px;width:100px;height:50px;", plain)
        val opaque = Wireframe(2, 0, 0, 5, 5, WireframeType.CONTAINER, background = 0xFFAABBCC.toInt())
        encoder.reset()
        assertTrue(style(elementFor(encoder.fullSnapshot(root(opaque), 0L), 2)).contains("background-color:#aabbcc;"))
    }

    @Test
    fun fullSnapshot_textIsChildTextNodeAndTextTagIsSpan() {
        val snap = encoder.fullSnapshot(root(label(2, "Hello")), 0L)
        val span = elementFor(snap, 2)
        assertEquals("span", span["tagName"])
        val text = span.list("childNodes").single()
        assertEquals(3, text["type"])
        assertEquals("Hello", text["textContent"])
        assertEquals(12, text["id"])
    }

    @Test
    fun fullSnapshot_maskedTextStaysMasked() {
        val tree = root(
            label(2, "****", masked = true),
            Wireframe(3, 0, 0, 10, 10, WireframeType.INPUT, text = "*******", masked = true),
            Wireframe(4, 0, 0, 10, 10, WireframeType.WEB, masked = true),
            Wireframe(5, 0, 0, 10, 10, WireframeType.CONTAINER, summarized = true),
        )
        val snap = encoder.fullSnapshot(tree, 0L)
        // Every textContent in the output is exactly a wireframe text or a fixed label, nothing else.
        assertEquals(listOf("****", "*******", "[blocked]", "[\u2026]"), textsOf(snap))
        assertEquals("true", (elementFor(snap, 2)["attributes"] as Map<*, *>)["data-artracker-masked"])
        // Also no text-like attribute values: only style / data-artracker-* attributes exist.
        for (node in allNodes(snap).filter { it["type"] == 2 }) {
            val keys = (node["attributes"] as Map<String, Any?>).keys
            assertTrue(keys.toString(), keys.all { it == "style" || it.startsWith("data-artracker-") })
        }
    }

    @Test
    fun fullSnapshot_onlyWireframeTextAppears() {
        val tree = root(
            label(2, "secret"),
            Wireframe(3, 0, 0, 10, 10, WireframeType.INPUT, text = "****", masked = true),
            Wireframe(4, 0, 0, 10, 10, WireframeType.IMAGE),
            Wireframe(5, 0, 0, 10, 10, WireframeType.WEB, masked = true),
            Wireframe(6, 0, 0, 10, 10, WireframeType.SURFACE, masked = true),
            Wireframe(7, 0, 0, 10, 10, WireframeType.COMPOSE_BLOCK, masked = true),
            Wireframe(8, 0, 0, 10, 10, WireframeType.CONTAINER, summarized = true),
            Wireframe(9, 0, 0, 10, 10, WireframeType.BUTTON, text = "Pay"),
        )
        val texts = textsOf(encoder.fullSnapshot(tree, 0L))
        assertEquals(listOf("secret", "****", "[blocked]", "[blocked]", "[blocked]", "[…]", "Pay"), texts)
    }

    @Test
    fun fullSnapshot_imageHasNoImgTagAndGreyPlaceholder() {
        val snap = encoder.fullSnapshot(root(Wireframe(2, 0, 0, 10, 10, WireframeType.IMAGE, background = 0xFFFF0000.toInt())), 0L)
        assertTrue(allNodes(snap).none { it["tagName"] == "img" })
        val image = elementFor(snap, 2)
        assertEquals("div", image["tagName"])
        assertTrue(style(image), style(image).contains("background-color:#cccccc;"))
        assertFalse(style(image).contains("ff0000"))
        assertTrue(image.list("childNodes").isEmpty())
    }

    @Test
    fun fullSnapshot_blockedTypesAreHatched() {
        val snap = encoder.fullSnapshot(root(Wireframe(2, 0, 0, 10, 10, WireframeType.WEB, masked = true)), 0L)
        assertTrue(style(elementFor(snap, 2)).contains("repeating-linear-gradient"))
    }

    @Test
    fun mutation_textsUseMappedIds() {
        val prev = root(label(2, "a"), box(3, 0, 0, 10, 10, label(4, "b")))
        val next = root(label(2, "a2"), box(3, 0, 0, 10, 10, label(4, "b2")))
        val snap = encoder.fullSnapshot(prev, 0L)
        val snapTextIds = allNodes(snap).filter { it["type"] == 3 }.associate { it["textContent"] to it["id"] }
        val event = encoder.mutation(WireframeDiffer.diff(prev, next), 5L)!!
        assertEquals(3, event["type"])
        assertEquals(5L, event["timestamp"])
        val data = event.map("data")
        assertEquals(0, data["source"])
        val texts = data.list("texts")
        assertEquals(listOf(snapTextIds["a"], snapTextIds["b"]), texts.map { it["id"] })
        assertEquals(listOf("a2", "b2"), texts.map { it["value"] })
        assertTrue(data.list("adds").isEmpty())
        assertTrue(data.list("removes").isEmpty())
        assertTrue(data.list("attributes").isEmpty())
    }

    @Test
    fun mutation_addsHaveParentAndNextId() {
        val prev = root(box(2), box(4))
        val next = root(box(2), box(3, 0, 0, 10, 10, label(5, "n")), box(4))
        val snap = encoder.fullSnapshot(prev, 0L)
        val id1 = elementFor(snap, 1)["id"]
        val id2 = elementFor(snap, 2)["id"]
        val id4 = elementFor(snap, 4)["id"]
        val data = encoder.mutation(WireframeDiffer.diff(prev, next), 1L)!!.map("data")
        // One entry per node, pre-order: the box, its label span, the label's text node.
        val adds = data.list("adds")
        assertEquals(3, adds.size)
        val boxAdd = adds[0]
        assertEquals(id1, boxAdd["parentId"])
        assertEquals(id4, boxAdd["nextId"])
        val boxNode = boxAdd.map("node")
        assertEquals(2, boxNode["type"])
        assertEquals(13, boxNode["id"])
        assertEquals(14, adds[1].map("node")["id"])
        assertEquals(15, adds[2].map("node")["id"])
        assertEquals("n", adds[2].map("node")["textContent"])
        assertNotNull(id2)
        // appended node: nextId null
        val appended = encoder.mutation(WireframeDiffer.diff(next, root(box(2), box(3, 0, 0, 10, 10, label(5, "n")), box(4), box(6))), 2L)!!
        assertNull(appended.map("data").list("adds").single()["nextId"])
        assertTrue(appended.map("data").list("adds").single().containsKey("nextId"))
    }

    @Test
    fun mutation_addedSubtree_isOneFlatEntryPerNode_parentBeforeChild_nextIdOnlyOnTheRoot() {
        val prev = root(box(2), box(4))
        val subtree = box(3, 0, 0, 40, 40, label(5, "hi", 1, 1), box(6, 2, 2, 10, 10, label(7, "yo", 3, 3)))
        val next = root(box(2), subtree, box(4))
        val snap = encoder.fullSnapshot(prev, 0L)
        val id1 = elementFor(snap, 1)["id"]
        val id4 = elementFor(snap, 4)["id"]
        val adds = encoder.mutation(WireframeDiffer.diff(prev, next), 1L)!!.map("data").list("adds")
        // box3, span5, text(hi), box6, span7, text(yo)
        assertEquals(6, adds.size)
        val ids = adds.map { it.map("node")["id"] as Int }
        assertEquals(ids.size, ids.toSet().size)
        // element nodes carry no children of their own
        for (a in adds) {
            val node = a.map("node")
            if (node["type"] == 2) assertTrue("element ${node["id"]} must not nest", (node["childNodes"] as? List<*>).orEmpty().isEmpty())
        }
        // parent always comes before its child
        val seen = HashSet<Any?>()
        seen += adds[0].map("node")["id"]
        for (a in adds.drop(1)) {
            assertTrue("parent ${a["parentId"]} added before child", a["parentId"] in seen || a["parentId"] == id1)
            seen += a.map("node")["id"]
        }
        // nextId only on the subtree root
        assertEquals(id4, adds[0]["nextId"])
        assertTrue(adds.drop(1).all { it.containsKey("nextId") && it["nextId"] == null })
        assertEquals(id1, adds[0]["parentId"])
        // the text node is its own entry under the span
        val span5 = adds[1].map("node")
        assertEquals("span", span5["tagName"])
        assertEquals(span5["id"], adds[2]["parentId"])
        assertEquals("hi", adds[2].map("node")["textContent"])
    }

    @Test
    fun container_withoutClip_doesNotClipItsChildren() {
        val s = styleOfNode(box(2))
        assertFalse(s, s.contains("overflow"))
    }

    @Test
    fun container_withClip_clips() {
        val s = styleOfNode(Wireframe(2, 0, 0, 10, 10, WireframeType.CONTAINER, visual = VisualStyle(clip = true)))
        assertTrue(s, s.endsWith("overflow:hidden;"))
    }

    @Test
    fun genericFamily_isPrefixedWithRoboto() {
        for (family in listOf("sans-serif", "serif", "monospace", "cursive")) {
            val s = styleOfNode(Wireframe(2, 0, 0, 50, 20, WireframeType.TEXT, text = "x", visual = VisualStyle(fontFamily = family)))
            assertTrue(s, s.contains("font-family:Roboto,$family;"))
        }
        val unknown = styleOfNode(Wireframe(2, 0, 0, 50, 20, WireframeType.TEXT, text = "x", visual = VisualStyle(fontFamily = "evil;}")))
        assertFalse(unknown, unknown.contains("font-family"))
    }

    @Test
    fun mutation_addUnderNestedParentUsesMappedParentIdAndRelativeCoords() {
        val prev = root(box(2, 10, 10, 100, 100))
        val next = root(box(2, 10, 10, 100, 100, box(3, 15, 18, 5, 5)))
        val snap = encoder.fullSnapshot(prev, 0L)
        val add = encoder.mutation(WireframeDiffer.diff(prev, next), 1L)!!.map("data").list("adds").single()
        assertEquals(elementFor(snap, 2)["id"], add["parentId"])
        assertTrue(style(add.map("node")).contains("left:5px;top:8px;"))
    }

    @Test
    fun mutation_removesUseMappedIds() {
        val prev = root(box(2, 0, 0, 10, 10, box(3)), box(4))
        val snap = encoder.fullSnapshot(prev, 0L)
        val rootId = elementFor(snap, 1)["id"]
        val removedId = elementFor(snap, 2)["id"]
        val event = encoder.mutation(WireframeDiffer.diff(prev, root(box(4))), 1L)!!
        val removes = event.map("data").list("removes")
        assertEquals(listOf(mapOf("parentId" to rootId, "id" to removedId)), removes)
        assertNull(encoder.nodeIdFor(3))
    }

    @Test
    fun mutation_boundsChangeEmitsStyleAttribute() {
        val prev = root(box(2, 0, 0, 10, 10))
        val next = root(box(2, 7, 0, 10, 10))
        val snap = encoder.fullSnapshot(prev, 0L)
        val attrs = encoder.mutation(WireframeDiffer.diff(prev, next), 1L)!!.map("data").list("attributes").single()
        assertEquals(elementFor(snap, 2)["id"], attrs["id"])
        val style = (attrs["attributes"] as Map<String, Any?>)["style"] as String
        assertTrue(style, style.contains("left:7px;"))
    }

    @Test
    fun mutation_maskedFlagChangeAddsAndRemovesAttribute() {
        val prev = root(box(2))
        val masked = root(box(2).copy(masked = true))
        encoder.fullSnapshot(prev, 0L)
        val on = encoder.mutation(WireframeDiffer.diff(prev, masked), 1L)!!.map("data").list("attributes").single()
        assertEquals("true", (on["attributes"] as Map<*, *>)["data-artracker-masked"])
        val off = encoder.mutation(WireframeDiffer.diff(masked, prev), 2L)!!.map("data").list("attributes").single()
        assertTrue((off["attributes"] as Map<*, *>).containsKey("data-artracker-masked"))
        assertNull((off["attributes"] as Map<*, *>)["data-artracker-masked"])
    }

    @Test
    fun mutation_parentShiftWithFollowingChildEmitsOnlyParent() {
        val prev = root(box(2, 10, 10, 100, 50, box(3, 12, 14, 20, 20)))
        val next = root(box(2, 30, 40, 100, 50, box(3, 32, 44, 20, 20)))
        encoder.fullSnapshot(prev, 0L)
        val attrs = encoder.mutation(WireframeDiffer.diff(prev, next), 1L)!!.map("data").list("attributes")
        assertEquals(1, attrs.size)
    }

    @Test
    fun mutation_emptyDiffIsNull() {
        encoder.fullSnapshot(root(), 0L)
        assertNull(encoder.mutation(WireframeDiff(emptyList(), emptyList(), emptyList(), emptyList()), 1L))
        val tree = root(box(2))
        assertNull(encoder.mutation(WireframeDiffer.diff(tree, tree), 1L))
    }

    @Test
    fun mutation_unknownReferencesAreSkippedNotCrashing() {
        // No snapshot taken: nothing is mapped, so nothing can be emitted.
        val diff = WireframeDiffer.diff(root(box(2), label(3, "a")), root(label(3, "b"), box(4)))
        assertNull(RrwebEncoder().mutation(diff, 1L))
    }

    @Test
    fun mutation_movedNodeGetsFreshIdsAndIsRemoveThenAdd() {
        val prev = root(box(2, 0, 0, 10, 10, label(5, "m")), box(3))
        val next = root(box(2), box(3, 0, 0, 10, 10, label(5, "m")))
        val snap = encoder.fullSnapshot(prev, 0L)
        val oldId = elementFor(snap, 5)["id"] as Int
        val data = encoder.mutation(WireframeDiffer.diff(prev, next), 1L)!!.map("data")
        assertEquals(oldId, data.list("removes").single()["id"])
        // the moved label (its span and its text node) is re-added flat, both with fresh ids
        val adds = data.list("adds")
        assertEquals(2, adds.size)
        assertTrue(adds.all { (it.map("node")["id"] as Int) > oldId })
    }

    @Test
    fun touch_values() {
        val down = encoder.touch(TouchKind.DOWN, 12, 34, 100L)
        assertEquals(3, down["type"])
        assertEquals(100L, down["timestamp"])
        assertEquals(mapOf("source" to 2, "type" to 7, "id" to 4, "x" to 12, "y" to 34), down.map("data"))
        val up = encoder.touch(TouchKind.UP, 1, 2, 101L)
        assertEquals(mapOf("source" to 2, "type" to 9, "id" to 4, "x" to 1, "y" to 2), up.map("data"))
        val move = encoder.touch(TouchKind.MOVE, 5, 6, 102L)
        assertEquals(3, move["type"])
        assertEquals(
            mapOf("source" to 6, "positions" to listOf(mapOf("x" to 5, "y" to 6, "id" to 4, "timeOffset" to 0))),
            move.map("data"),
        )
    }

    @Test
    fun meta_hrefScrubbed() {
        val meta = encoder.meta("android://checkout?token=abc123&x=1", 360, 640, 9L)
        assertEquals(4, meta["type"])
        assertEquals(9L, meta["timestamp"])
        assertEquals(
            mapOf("href" to "android://checkout?token=[redacted]&x=1", "width" to 360, "height" to 640),
            meta.map("data"),
        )
    }

    @Test
    fun encoder_reset_restartsIds() {
        val tree = root(box(2), label(3, "x"))
        val first = encoder.fullSnapshot(tree, 0L)
        // grow the counter with a mutation add, then reset
        encoder.mutation(WireframeDiffer.diff(tree, root(box(2), label(3, "x"), box(9))), 1L)
        encoder.reset()
        val second = encoder.fullSnapshot(tree, 0L)
        assertEquals(first, second)
        assertEquals(10, firstDiv(second)["id"])
    }

    @Test
    fun fullSnapshot_assignsSequentialIdsFrom10InPreOrder() {
        val snap = encoder.fullSnapshot(root(box(2, 0, 0, 10, 10, label(3, "a")), box(4)), 0L)
        val ids = allNodes(snap).map { it["id"] }
        assertEquals(listOf(1, 2, 3, 4, 5, STYLE_NODE_ID, STYLE_TEXT_NODE_ID, 10, 11, 12, 13, 14), ids.map { it as Int }.sorted())
        assertEquals(10, elementFor(snap, 1)["id"])
        assertEquals(11, elementFor(snap, 2)["id"])
        assertEquals(12, elementFor(snap, 3)["id"])
        assertEquals(14, elementFor(snap, 4)["id"])
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun events_areJsonSafe() {
        val tree = root(box(2, 0, 0, 10, 10, label(3, "quote\" \\ \n  é")), box(4))
        val next = root(box(2, 5, 0, 10, 10, label(3, "other")), box(5))
        val events = listOf(
            encoder.meta("android://home", 360, 640, 1L),
            encoder.fullSnapshot(tree, 2L),
            encoder.mutation(WireframeDiffer.diff(tree, next), 3L)!!,
            encoder.touch(TouchKind.DOWN, 1, 2, 4L),
            encoder.touch(TouchKind.MOVE, 1, 2, 5L),
            encoder.touch(TouchKind.UP, 1, 2, 6L),
        )
        for (event in events) {
            val json = Json.encode(event)
            val parsed = JSONObject(json)
            assertTrue(parsed.has("type"))
            assertTrue(parsed.has("timestamp"))
            assertFalse(json, json.contains("[max_depth]"))
        }
        val snapParsed = JSONObject(Json.encode(events[1]))
        assertEquals(2, snapParsed.getInt("type"))
        assertTrue(snapParsed.getJSONObject("data").getJSONObject("node").getJSONArray("childNodes") is JSONArray)
    }

    @Test
    fun fullSnapshot_neverEmitsTextBeyondWireframeText() {
        val tree = root(
            Wireframe(2, 0, 0, 10, 10, WireframeType.INPUT, text = "****", masked = true, textColor = 5),
            Wireframe(3, 0, 0, 10, 10, WireframeType.WEB, masked = true),
            label(4, "visible"),
        )
        val allowed = setOf("****", "[blocked]", "visible", "[…]")
        assertTrue(textsOf(encoder.fullSnapshot(tree, 0L)).all { it in allowed })
    }

    @Test
    fun mutation_addedChildUsesFinalGeometryOfMovedParent() {
        val prev = root(box(2, 10, 10, 100, 100))
        val next = root(box(2, 30, 40, 100, 100, box(3, 35, 45, 5, 5)))
        val snap = encoder.fullSnapshot(prev, 0L)
        val data = encoder.mutation(WireframeDiffer.diff(prev, next), 1L)!!.map("data")
        val childStyle = style(data.list("adds").single().map("node"))
        assertTrue(childStyle, childStyle.contains("left:5px;top:5px;"))
        val attrs = data.list("attributes").single()
        assertEquals(elementFor(snap, 2)["id"], attrs["id"])
        val parentStyle = (attrs["attributes"] as Map<String, Any?>)["style"] as String
        assertTrue(parentStyle, parentStyle.contains("left:30px;top:40px;"))
    }

    @Test
    fun mutation_addedGrandchildWhenGrandparentMovesAndParentStaysInScreenSpace() {
        // Grandparent moves (0,0)->(50,50); parent stays at screen (20,20), so its relative offset changes too.
        val prev = root(box(2, 0, 0, 200, 200, box(3, 20, 20, 100, 100)))
        val next = root(box(2, 50, 50, 200, 200, box(3, 20, 20, 100, 100, box(4, 25, 27, 5, 5))))
        val snap = encoder.fullSnapshot(prev, 0L)
        val data = encoder.mutation(WireframeDiffer.diff(prev, next), 1L)!!.map("data")
        val grandchild = data.list("adds").single()
        assertEquals(elementFor(snap, 3)["id"], grandchild["parentId"])
        assertTrue(style(grandchild.map("node")), style(grandchild.map("node")).contains("left:5px;top:7px;"))
        val byId = data.list("attributes").associate { it["id"] to (it["attributes"] as Map<String, Any?>)["style"] as String }
        assertTrue(byId.getValue(elementFor(snap, 2)["id"]).contains("left:50px;top:50px;"))
        assertTrue(byId.getValue(elementFor(snap, 3)["id"]).contains("left:-30px;top:-30px;"))
    }

    @Test
    fun mutation_addedGrandchildWhenGrandparentAndParentMoveTogether() {
        // Stale-geometry discriminator: the parent's absolute position changes (but not its relative one).
        val prev = root(box(2, 0, 0, 200, 200, box(3, 20, 20, 100, 100)))
        val next = root(box(2, 50, 50, 200, 200, box(3, 70, 70, 100, 100, box(4, 75, 77, 5, 5))))
        val snap = encoder.fullSnapshot(prev, 0L)
        val data = encoder.mutation(WireframeDiffer.diff(prev, next), 1L)!!.map("data")
        val grandchild = data.list("adds").single()
        assertEquals(elementFor(snap, 3)["id"], grandchild["parentId"])
        assertTrue(style(grandchild.map("node")), style(grandchild.map("node")).contains("left:5px;top:7px;"))
        assertEquals(listOf(elementFor(snap, 2)["id"]), data.list("attributes").map { it["id"] })
    }

    // ---- mutation applied to the snapshot DOM must equal a fresh snapshot of the next frame ----

    private class DomNode(
        var id: Int,
        val kind: Int,
        val tagName: String?,
        val attrs: MutableMap<String, Any?>,
        var text: String?,
        val children: MutableList<DomNode> = ArrayList(),
    )

    private class Dom(snapshot: Map<String, Any?>) {
        val byId = HashMap<Int, DomNode>()
        val parentOf = HashMap<Int, DomNode>()
        val document: DomNode = build(snapshot.map("data").map("node"))

        private fun Map<String, Any?>.map(key: String) = this[key] as Map<String, Any?>

        fun build(m: Map<String, Any?>): DomNode {
            val node = DomNode(
                m["id"] as Int, m["type"] as Int, m["tagName"] as String?,
                ((m["attributes"] as? Map<String, Any?>) ?: emptyMap()).toMutableMap(), m["textContent"] as String?,
            )
            assertFalse("duplicate node id ${node.id}", byId.containsKey(node.id))
            byId[node.id] = node
            for (c in (m["childNodes"] as? List<Map<String, Any?>>).orEmpty()) {
                val child = build(c)
                node.children.add(child)
                parentOf[child.id] = node
            }
            return node
        }

        private fun detach(node: DomNode) {
            byId.remove(node.id)
            node.children.forEach(::detach)
            parentOf.remove(node.id)
        }

        /** rrweb order: texts, attributes, removes, adds (per the replayer's applyMutation). */
        fun apply(event: Map<String, Any?>) {
            val data = event["data"] as Map<String, Any?>
            for (t in data["texts"] as List<Map<String, Any?>>) {
                byId.getValue(t["id"] as Int).text = t["value"] as String
            }
            for (a in data["attributes"] as List<Map<String, Any?>>) {
                val node = byId.getValue(a["id"] as Int)
                for ((k, v) in a["attributes"] as Map<String, Any?>) if (v == null) node.attrs.remove(k) else node.attrs[k] = v
            }
            for (r in data["removes"] as List<Map<String, Any?>>) {
                val node = byId.getValue(r["id"] as Int)
                val parent = parentOf.getValue(node.id)
                assertEquals("remove parentId", r["parentId"], parent.id)
                parent.children.remove(node)
                detach(node)
            }
            for (a in data["adds"] as List<Map<String, Any?>>) {
                val parent = byId.getValue(a["parentId"] as Int)
                val node = build(a["node"] as Map<String, Any?>)
                parentOf[node.id] = parent
                val nextId = a["nextId"] as Int?
                if (nextId == null) {
                    parent.children.add(node)
                } else {
                    val idx = parent.children.indexOfFirst { it.id == nextId }
                    assertTrue("nextId $nextId must be a child of ${parent.id}", idx >= 0)
                    parent.children.add(idx, node)
                }
            }
        }

        /** Structure + attributes + text, with node ids stripped. */
        fun normalized(node: DomNode = document): Any =
            mapOf(
                "kind" to node.kind, "tag" to node.tagName, "attrs" to node.attrs.toMap(), "text" to node.text,
                "children" to node.children.map { normalized(it) },
            )
    }

    private fun assertMutationEqualsSnapshot(prev: Wireframe, next: Wireframe) {
        val enc = RrwebEncoder()
        val dom = Dom(enc.fullSnapshot(prev, 0L))
        val event = enc.mutation(WireframeDiffer.diff(prev, next), 1L)
        if (event != null) dom.apply(event)
        val expected = Dom(RrwebEncoder().fullSnapshot(next, 0L))
        assertEquals(expected.normalized(), dom.normalized())
        // The encoder must also keep working on the NEXT diff after this mutation (state stayed in sync).
        val again = enc.mutation(WireframeDiffer.diff(next, next.copy()), 2L)
        assertNull(again)
    }

    @Test
    fun applied_textChange() = assertMutationEqualsSnapshot(
        root(label(2, "a"), box(3, 0, 0, 10, 10, label(4, "b"))),
        root(label(2, "a2"), box(3, 0, 0, 10, 10, label(4, "b2"))),
    )

    @Test
    fun applied_addAndRemove() = assertMutationEqualsSnapshot(
        root(box(2), box(3, 0, 0, 10, 10, label(5, "x")), box(4)),
        root(box(2), box(6, 1, 2, 10, 10, label(7, "y")), box(4), box(8)),
    )

    @Test
    fun applied_addInTheMiddleAndAtStart() = assertMutationEqualsSnapshot(
        root(box(2), box(4)),
        root(box(5), box(2), box(3, 0, 0, 10, 10, box(6)), box(4)),
    )

    @Test
    fun applied_reorder() = assertMutationEqualsSnapshot(
        root(box(2), label(3, "t"), box(4), box(5)),
        root(box(5), box(2), box(4), label(3, "t")),
    )

    @Test
    fun applied_reorderWithAddAndRemove() = assertMutationEqualsSnapshot(
        root(box(2), box(3), box(4), box(5)),
        root(box(4), box(6), box(2), box(5)),
    )

    @Test
    fun applied_movedParentWithAddedChild() = assertMutationEqualsSnapshot(
        root(box(2, 10, 10, 100, 100)),
        root(box(2, 30, 40, 100, 100, box(3, 35, 45, 5, 5))),
    )

    @Test
    fun applied_movedParentWithFollowingAndStationaryChildren() = assertMutationEqualsSnapshot(
        root(box(2, 10, 10, 100, 100, box(3, 12, 14, 5, 5), box(4, 20, 20, 5, 5))),
        root(box(2, 30, 40, 100, 100, box(3, 32, 44, 5, 5), box(4, 20, 20, 5, 5), box(5, 31, 41, 3, 3))),
    )

    @Test
    fun applied_movedGrandparentWithAddedGrandchild() = assertMutationEqualsSnapshot(
        root(box(2, 0, 0, 200, 200, box(3, 20, 20, 100, 100))),
        root(box(2, 50, 50, 200, 200, box(3, 20, 20, 100, 100, box(4, 25, 27, 5, 5)))),
    )

    @Test
    fun applied_movedGrandparentAndParentWithAddedGrandchild() = assertMutationEqualsSnapshot(
        root(box(2, 0, 0, 200, 200, box(3, 20, 20, 100, 100))),
        root(box(2, 50, 50, 200, 200, box(3, 70, 70, 100, 100, box(4, 75, 77, 5, 5)))),
    )

    @Test
    fun applied_movedSubtreeToAnotherParent() = assertMutationEqualsSnapshot(
        root(box(2, 10, 10, 100, 100, box(5, 12, 12, 10, 10, label(6, "m"))), box(3, 200, 200, 100, 100)),
        root(box(2, 10, 10, 100, 100), box(3, 200, 200, 100, 100, box(5, 205, 206, 10, 10, label(6, "m")))),
    )

    @Test
    fun applied_maskedAndStyleChanges() = assertMutationEqualsSnapshot(
        root(box(2), label(3, "a")),
        root(box(2).copy(masked = true, alpha = 0.5f, background = 0xFF123456.toInt()), label(3, "a").copy(textColor = 5, textSizeSp = 12f)),
    )

    @Test
    fun applied_rootReplaced() = assertMutationEqualsSnapshot(
        box(1, 0, 0, 360, 640, label(2, "a")),
        box(9, 0, 0, 360, 640, label(2, "a")),
    )

    @Test
    fun applied_chainOfFramesStaysInSync() {
        val frames = listOf(
            root(box(2, 10, 10, 100, 100, label(3, "a")), box(4)),
            root(box(2, 30, 40, 100, 100, label(3, "a"), box(5, 33, 44, 4, 4)), box(4)),
            root(box(4), box(2, 30, 40, 100, 100, label(3, "b"), box(5, 33, 44, 4, 4))),
            root(box(4, 0, 0, 100, 50, box(2, 30, 40, 100, 100, box(5, 36, 46, 4, 4))), box(6, 1, 1, 2, 2)),
        )
        val enc = RrwebEncoder()
        val dom = Dom(enc.fullSnapshot(frames[0], 0L))
        for (i in 1 until frames.size) {
            enc.mutation(WireframeDiffer.diff(frames[i - 1], frames[i]), i.toLong())?.let { dom.apply(it) }
            assertEquals("frame $i", Dom(RrwebEncoder().fullSnapshot(frames[i], 0L)).normalized(), dom.normalized())
        }
    }

    private companion object {
        const val STYLE_NODE_ID = 6
        const val STYLE_TEXT_NODE_ID = 7
    }
}
