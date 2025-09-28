package com.example.smartdriver.map

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdriver.R
import com.example.smartdriver.zones.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import kotlin.math.max

class MapEditorActivity : AppCompatActivity() {

    private var mapView: MapView? = null
    private lateinit var zonesRender: ZonesRenderOverlay
    private lateinit var polygonEditor: PolygonEditorOverlay

    private lateinit var drawer: DrawerLayout
    private lateinit var contentRoot: LinearLayout
    private lateinit var mapContainer: FrameLayout

    private lateinit var zonesList: RecyclerView
    private lateinit var zonesAdapter: ZonesAdapter

    private var zoneCounter = 0
    private var editingZone: Zone? = null

    private val repoListener = object : ZoneRepository.SaveListener {
        override fun onDirty() {}
        override fun onSaved(success: Boolean) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { supportActionBar?.hide() } catch (_: Throwable) {}

        // Drawer + layout
        drawer = DrawerLayout(this).apply {
            layoutParams = DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT
            )
            setScrimColor(0x44000000)
        }
        contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT
            )
        }
        val topTitleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
        }
        val titleView = TextView(this).apply {
            text = "Editor de Zonas"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 18f; gravity = Gravity.CENTER
        }
        topTitleBar.addView(titleView)

        mapContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }
        val backText = TextView(this).apply {
            text = "↩ Voltar ao menu anterior"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            setOnClickListener { finish() }
        }
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
        val btnZones = Button(this).apply {
            text = "Zonas"; setOnClickListener {
            if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START)
            else drawer.openDrawer(GravityCompat.START)
        }
        }
        val btnSave = Button(this).apply {
            text = "Guardar"; setOnClickListener {
            try { ZoneRepository.save() } catch (_: Throwable) {}
            Toast.makeText(this@MapEditorActivity, "Zonas guardadas", Toast.LENGTH_SHORT).show()
            finish()
        }
        }
        val btnMore = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            background = null
            contentDescription = "Mais opções"
            setOnClickListener { showOverflowMenu(this) }
        }
        bottomBar.addView(backText); bottomBar.addView(spacer)
        bottomBar.addView(btnZones); bottomBar.addView(btnSave); bottomBar.addView(btnMore)

        contentRoot.addView(topTitleBar); contentRoot.addView(mapContainer); contentRoot.addView(bottomBar)

        val drawerWidth = (300 * resources.displayMetrics.density).toInt()
        val drawerRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = DrawerLayout.LayoutParams(drawerWidth, DrawerLayout.LayoutParams.MATCH_PARENT, GravityCompat.START)
            setBackgroundColor(0xFFF7F7F7.toInt())
        }
        val drawerHeader = TextView(this).apply {
            text = "ZONAS"; setPadding(dp(16), dp(18), dp(16), dp(12)); textSize = 16f; setTextColor(0xFF37474F.toInt())
        }
        zonesList = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MapEditorActivity) }
        drawerRoot.addView(drawerHeader)
        drawerRoot.addView(zonesList, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        drawer.addView(contentRoot); drawer.addView(drawerRoot)
        setContentView(drawer)

        // Repo
        ZoneRepository.init(applicationContext); ZoneRepository.addListener(repoListener)

        // OSMDroid config/cache
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        val cfg = Configuration.getInstance()
        cfg.load(ctx, prefs); cfg.userAgentValue = packageName
        val basePath = java.io.File(ctx.cacheDir, "osmdroid").apply { mkdirs() }
        val tilePath = java.io.File(basePath, "tiles").apply { mkdirs() }
        cfg.osmdroidBasePath = basePath; cfg.osmdroidTileCache = tilePath

        // MapView
        val mv = MapView(this)
        mv.setTileSource(TileSourceFactory.MAPNIK)
        mv.setMultiTouchControls(true)
        try { mv.setMinZoomLevel(2.0) } catch (_: Throwable) {}
        try { mv.setMaxZoomLevel(21.0) } catch (_: Throwable) {}
        // 👇 aplicar estilo nítido a cores
        MapStyle.applyHighContrastColorStyle(mv)

        mapContainer.addView(mv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mapView = mv

        // Overlays
        zonesRender = ZonesRenderOverlay(this).apply {
            onRequestEdit = { z -> enterEditMode(z) }
        }
        mv.overlays.add(zonesRender)

        polygonEditor = PolygonEditorOverlay(this).apply {
            onChange = { /* autosave já trata sujidade */ }
            onFinalize = { pts, type ->
                val editing = editingZone
                if (editing != null) {
                    editing.points = pts.toMutableList()
                    editing.active = true
                    editing.touch()
                    editing.style = editing.style ?: ZoneDefaults.styleFor(editing.type)
                    ZoneRepository.update(editing)
                    editingZone = null
                    Toast.makeText(this@MapEditorActivity, "Zona atualizada.", Toast.LENGTH_SHORT).show()
                } else {
                    zoneCounter += 1
                    val zone = Zone(
                        name = "Zona $zoneCounter",
                        type = type,
                        points = pts.toMutableList(),
                        style = ZoneDefaults.styleFor(type)
                    )
                    ZoneRepository.add(zone)
                    Toast.makeText(this@MapEditorActivity, "Zona adicionada (#$zoneCounter).", Toast.LENGTH_SHORT).show()
                }
                refreshZonesUi()
                mapView?.invalidate()
            }
            // <<< Long-press: pedir edição da zona por baixo do dedo
            onLongPressRequestEdit = { gp ->
                val z = pickZoneAt(gp.latitude, gp.longitude)
                if (z != null) enterEditMode(z)
                else Toast.makeText(this@MapEditorActivity, "Sem zona por baixo do dedo.", Toast.LENGTH_SHORT).show()
            }
        }
        mv.overlays.add(polygonEditor)

        // Adapter + drag
        zonesAdapter = ZonesAdapter(
            onToggleActive = { z, active ->
                z.active = active; z.touch(); ZoneRepository.update(z); mapView?.invalidate()
            },
            onDelete = { z ->
                confirm("Apagar \"${z.name}\"?") { ZoneRepository.delete(z.id); refreshZonesUi(); mapView?.invalidate() }
            },
            onRename = { z ->
                prompt("Renomear zona", z.name) { newName: String ->
                    z.name = newName; z.touch(); ZoneRepository.update(z); refreshZonesUi()
                }
            },
            onCycleType = { z ->
                z.type = when (z.type) {
                    ZoneType.NO_GO -> ZoneType.SOFT_AVOID
                    ZoneType.SOFT_AVOID -> ZoneType.PREFERRED
                    ZoneType.PREFERRED -> ZoneType.NO_GO
                }
                z.style = ZoneDefaults.styleFor(z.type)
                z.touch(); ZoneRepository.update(z)
                mapView?.invalidate(); refreshZonesUi()
            },
            onEdit = { z -> enterEditMode(z) }
        )
        zonesList.adapter = zonesAdapter
        attachDragToRecycler()

        val lat = savedInstanceState?.getDouble(STATE_CENTER_LAT) ?: 38.708
        val lon = savedInstanceState?.getDouble(STATE_CENTER_LON) ?: -9.136
        val zoom = savedInstanceState?.getDouble(STATE_ZOOM) ?: 14.5
        mv.controller.setZoom(zoom); mv.controller.setCenter(GeoPoint(lat, lon))

        refreshZonesUi()
    }

    // ---------- Overflow (⋮) ----------
    private fun showOverflowMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, ID_TEST_GEOFENCE, 1, "Testar geofencing (centro)")
        pm.menu.add(0, ID_UNDO, 2, "Undo")
        pm.menu.add(0, ID_REDO, 3, "Redo")
        pm.menu.add(0, ID_CLEAR, 4, "Limpar")
        val nextLabel = if (polygonEditor.mode == PolygonEditorOverlay.Mode.PONTOS)
            "Modo: Pontos → Desenho livre" else "Modo: Desenho livre → Pontos"
        pm.menu.add(0, ID_TOGGLE_MODE, 5, nextLabel)
        pm.menu.add(0, ID_STATUS, 6, "Estado/Ajuda")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_TEST_GEOFENCE -> { testGeofenceAtCenter(); true }
                ID_UNDO -> { if (!polygonEditor.undo()) Toast.makeText(this, "Nada para desfazer", Toast.LENGTH_SHORT).show() else mapView?.invalidate(); true }
                ID_REDO -> { if (!polygonEditor.redo()) Toast.makeText(this, "Nada para refazer", Toast.LENGTH_SHORT).show() else mapView?.invalidate(); true }
                ID_CLEAR -> { polygonEditor.clear(); mapView?.invalidate(); Toast.makeText(this, "Editor limpo", Toast.LENGTH_SHORT).show(); true }
                ID_TOGGLE_MODE -> { toggleEditorMode(); true }
                ID_STATUS -> { showStatusDialog(); true }
                else -> false
            }
        }
        pm.show()
    }

    private fun toggleEditorMode() {
        val newMode = if (polygonEditor.mode == PolygonEditorOverlay.Mode.PONTOS)
            PolygonEditorOverlay.Mode.DESENHO_LIVRE else PolygonEditorOverlay.Mode.PONTOS
        polygonEditor.mode = newMode
        Toast.makeText(this, "Modo: ${if (newMode == PolygonEditorOverlay.Mode.PONTOS) "Pontos" else "Desenho livre"}", Toast.LENGTH_SHORT).show()
    }

    // ---------- Long-press hit-test ----------
    private fun pickZoneAt(lat: Double, lon: Double): Zone? {
        val zones = ZoneRepository.list().filter { it.points.size >= 3 }
        for (z in zones.asReversed()) { // último por cima
            if (containsPoint(lat, lon, z.points)) return z
        }
        return null
    }

    private fun containsPoint(lat: Double, lon: Double, pts: List<GeoPoint>): Boolean {
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val xi = pts[i].latitude
            val yi = pts[i].longitude
            val xj = pts[j].latitude
            val yj = pts[j].longitude
            val intersect = ((yi > lon) != (yj > lon)) &&
                    (lat < (xj - xi) * (lon - yi) / ((yj - yi) + 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    // ---------- Helpers visuais ----------
    private fun testGeofenceAtCenter() {
        val gp = mapView?.mapCenter as? GeoPoint ?: return
        val hit = ZoneRuntime.firstZoneMatch(gp.latitude, gp.longitude)
        val msg = if (hit == null) "Centro: fora de zonas"
        else "Centro: dentro de \"${hit.name}\" (${hit.type})"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        ZoneRuntime.updatePosition(gp.latitude, gp.longitude)
    }

    private fun showStatusDialog() {
        val cur = ZoneRuntime.current()?.let { "Geofence: ${it.name} (${it.type})" } ?: "Geofence: fora"
        val text = buildString {
            append(cur).append('\n')
            append("Zonas ativas: ")
                .append(ZoneRepository.list().count { it.active })
                .append(" / ").append(ZoneRepository.size()).append('\n')
            append("• Modo atual: ").append(if (polygonEditor.mode == PolygonEditorOverlay.Mode.PONTOS) "Pontos" else "Desenho livre").append('\n')
            append("• PONTOS: toque para adicionar, duplo toque para terminar, arraste os handles para ajustar.\n")
            append("• DESENHO LIVRE: deslize o dedo para desenhar; ao levantar finaliza.\n")
            append("• Long-press numa zona para editar.\n")
            append("• Botões: Zonas (drawer) | Guardar | ⋮ opções")
        }
        AlertDialog.Builder(this)
            .setTitle("Editor de Zonas")
            .setMessage(text)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sim") { d, _ -> d.dismiss(); onYes() }
            .setNegativeButton("Não") { d, _ -> d.dismiss() }
            .show()
    }

    private fun prompt(title: String, current: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current); setSelection(current.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { d, _ ->
                val txt = input.text?.toString()?.trim().orEmpty()
                if (txt.isNotEmpty()) onOk(txt)
                d.dismiss()
            }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
            .show()
    }

    private fun attachDragToRecycler() {
        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition; val to = target.adapterPosition
                zonesAdapter.swap(from, to); return true
            }
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) commitOrderToRepository()
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = false
        }
        val ith = ItemTouchHelper(callback)
        ith.attachToRecyclerView(zonesList)
        zonesAdapter.onStartDrag = { vh -> ith.startDrag(vh) }
    }

    private fun commitOrderToRepository() {
        val newList = zonesAdapter.current().toMutableList()
        newList.forEachIndexed { index, zone -> zone.priority = index }
        ZoneRepository.setAll(newList)
        mapView?.invalidate()
    }

    private fun enterEditMode(z: Zone) {
        z.active = false; ZoneRepository.update(z)
        polygonEditor.setPolygon(z.points, z.type)
        editingZone = z
        Toast.makeText(this, "Editar: ${z.name} — ajusta handles e termina com duplo toque (Pontos) ou levantar o dedo (Desenho livre).", Toast.LENGTH_LONG).show()
        mapView?.invalidate()
        refreshZonesUi()
        drawer.closeDrawer(GravityCompat.START)
    }

    private fun refreshZonesUi() { zonesAdapter.submit(ZoneRepository.list()) }

    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() {
        super.onPause()
        try { if (ZoneRepository.hasPendingWrites()) ZoneRepository.save() } catch (_: Throwable) {}
        mapView?.onPause()
    }
    override fun onDestroy() { super.onDestroy(); ZoneRepository.removeListener(repoListener) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val p = mapView?.mapCenter as? GeoPoint
        if (p != null) {
            outState.putDouble(STATE_CENTER_LAT, p.latitude)
            outState.putDouble(STATE_CENTER_LON, p.longitude)
        }
        mapView?.zoomLevelDouble?.let { outState.putDouble(STATE_ZOOM, it) }
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    companion object {
        private const val STATE_CENTER_LAT = "state_center_lat"
        private const val STATE_CENTER_LON = "state_center_lon"
        private const val STATE_ZOOM = "state_zoom"

        private const val ID_TEST_GEOFENCE = 201
        private const val ID_UNDO = 202
        private const val ID_REDO = 203
        private const val ID_CLEAR = 204
        private const val ID_TOGGLE_MODE = 206
        private const val ID_STATUS = 205

        fun start(context: Context) {
            val intent = Intent(context, MapEditorActivity::class.java)
            context.startActivity(intent)
        }
    }

    // ======== Adapter ========
    private inner class ZonesAdapter(
        val onToggleActive: (Zone, Boolean) -> Unit,
        val onDelete: (Zone) -> Unit,
        val onRename: (Zone) -> Unit,
        val onCycleType: (Zone) -> Unit,
        val onEdit: (Zone) -> Unit
    ) : RecyclerView.Adapter<ZonesAdapter.VH>() {

        private val data: MutableList<Zone> = mutableListOf()
        var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

        fun submit(list: List<Zone>) {
            data.clear()
            data.addAll(list.sortedBy { it.priority })
            notifyDataSetChanged()
        }

        fun current(): List<Zone> = data.toList()

        fun swap(from: Int, to: Int) {
            if (from == to) return
            val a = data.removeAt(from)
            data.add(if (to >= data.size) data.size else max(0, to), a)
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_zone, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) { h.bind(data[pos]) }

        override fun getItemCount(): Int = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val handle: View = v.findViewById(R.id.handle)
            private val name: TextView = v.findViewById(R.id.name)
            private val type: TextView = v.findViewById(R.id.type)
            private val active: Switch = v.findViewById(R.id.activeSwitch)
            private val deleteBtn: ImageButton = v.findViewById(R.id.deleteBtn)
            private val root: View = v.findViewById(R.id.root)

            fun bind(z: Zone) {
                name.text = z.name
                type.text = z.type.name
                active.isChecked = z.active
                type.setTextColor(
                    when (z.type) {
                        ZoneType.NO_GO -> 0xFFD32F2F.toInt()
                        ZoneType.SOFT_AVOID -> 0xFFF57C00.toInt()
                        ZoneType.PREFERRED -> 0xFF388E3C.toInt()
                    }
                )
                handle.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        onStartDrag?.invoke(this@VH); true
                    } else false
                }
                active.setOnCheckedChangeListener(null)
                active.setOnCheckedChangeListener { _, isChecked -> onToggleActive(z, isChecked) }
                deleteBtn.setOnClickListener { onDelete(z) }

                name.setOnClickListener { onRename(z) }
                type.setOnClickListener { onCycleType(z) }
                root.setOnClickListener { onEdit(z) }
            }
        }
    }
}
