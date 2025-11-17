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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import org.osmdroid.util.GeoPoint
import kotlin.math.max

class MapEditorActivity : AppCompatActivity(), OnMapReadyCallback {

    private var gmap: GoogleMap? = null
    private lateinit var drawer: DrawerLayout
    private lateinit var contentRoot: LinearLayout
    private lateinit var mapContainer: FrameLayout

    private lateinit var zonesList: RecyclerView
    private lateinit var zonesAdapter: ZonesAdapter

    private lateinit var zonesRender: ZonesRenderOverlay
    private var polygonEditor: PolygonEditorOverlay? = null

    private var zoneCounter = 0
    private var editingZone: Zone? = null

    private val repoListener = object : ZoneRepository.SaveListener {
        override fun onDirty() { redrawZones() }
        override fun onSaved(success: Boolean) { redrawZones() }
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
            id = View.generateViewId()
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
        } }
        val btnSave = Button(this).apply {
            text = "Guardar"; setOnClickListener {
            try { ZoneRepository.save() } catch (_: Throwable) {}
            Toast.makeText(this@MapEditorActivity, "Zonas guardadas", Toast.LENGTH_SHORT).show()
            finish()
        } }
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
        ZoneRepository.init(applicationContext)
        ZoneRepository.addListener(repoListener)

        // MapFragment
        val frag = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(mapContainer.id, frag)
            .commitNow()
        frag.getMapAsync(this)

        // Lista/adapter
        zonesAdapter = ZonesAdapter(
            onToggleActive = { z, active ->
                z.active = active; z.touch(); ZoneRepository.update(z); redrawZones()
            },
            onDelete = { z ->
                confirm("Apagar \"${z.name}\"?") {
                    ZoneRepository.delete(z.id)
                    polygonEditor?.clear()
                    refreshZonesUi()
                    redrawZones()
                }
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
                redrawZones(); refreshZonesUi()
            },
            onEdit = { z -> enterEditMode(z) }
        )
        zonesList.adapter = zonesAdapter
        attachDragToRecycler()

        refreshZonesUi()
    }

    override fun onMapReady(map: GoogleMap) {
        gmap = map
        try { gmap!!.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.sd_light_style)) } catch (_: Exception) {}

        gmap!!.uiSettings.isMapToolbarEnabled = false
        gmap!!.uiSettings.isZoomControlsEnabled = false
        gmap!!.uiSettings.isCompassEnabled = true
        gmap!!.uiSettings.isMyLocationButtonEnabled = false
        gmap!!.uiSettings.setAllGesturesEnabled(true)

        gmap!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(38.708, -9.136), 14.5f))

        // Render + Editor
        zonesRender = ZonesRenderOverlay(this, gmap!!)
        polygonEditor = PolygonEditorOverlay(this, gmap!!, mapContainer).apply {
            // callback de alteração (opcional)
            onChange = { /* podes mostrar instrução no UI, se quiseres */ }

            // ⛳️ FINALIZAR: cria OU atualiza
            onFinalize = { ptsGeo, type ->
                val editing = editingZone
                if (editing != null) {
                    editing.points = ptsGeo.toMutableList()
                    editing.active = true
                    editing.style = editing.style ?: ZoneDefaults.styleFor(editing.type)
                    editing.touch()
                    ZoneRepository.update(editing)
                    editingZone = null
                    Toast.makeText(this@MapEditorActivity, "Zona atualizada.", Toast.LENGTH_SHORT).show()
                } else {
                    zoneCounter += 1
                    val zone = Zone(
                        name = "Zona $zoneCounter",
                        type = type,
                        points = ptsGeo.toMutableList(),
                        style = ZoneDefaults.styleFor(type)
                    )
                    ZoneRepository.add(zone)
                    Toast.makeText(this@MapEditorActivity, "Zona adicionada (#$zoneCounter).", Toast.LENGTH_SHORT).show()
                }
                refreshZonesUi()
                redrawZones()
            }
        }

        // TAP → por pontos (já tratado dentro do editor)
        gmap!!.setOnMapClickListener { latLng -> polygonEditor?.onMapTap(latLng) }

        // LONG-PRESS → entrar em edição da zona sob o dedo
        gmap!!.setOnMapLongClickListener { latLng ->
            val z = pickZoneAt(latLng.latitude, latLng.longitude)
            if (z != null) enterEditMode(z)
            else Toast.makeText(this, "Sem zona por baixo do dedo.", Toast.LENGTH_SHORT).show()
        }

        // mantém overlay colado ao mapa
        gmap!!.setOnCameraMoveListener { polygonEditor?.invalidateOverlay() }
        gmap!!.setOnCameraIdleListener { polygonEditor?.invalidateOverlay() }

        redrawZones()
    }

    // ---------- Overflow ----------
    private fun showOverflowMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, ID_TEST_GEOFENCE, 1, "Testar geofencing (centro)")
        pm.menu.add(0, ID_UNDO, 2, "Undo")
        pm.menu.add(0, ID_REDO, 3, "Redo")
        pm.menu.add(0, ID_CLEAR, 4, "Limpar")
        pm.menu.add(0, ID_TOGGLE_MODE, 5,
            if (polygonEditor?.mode == PolygonEditorOverlay.Mode.PONTOS)
                "Modo: Pontos → Desenho livre" else "Modo: Desenho livre → Pontos"
        )
        pm.menu.add(0, ID_FINISH, 6, "Concluir polígono")
        pm.menu.add(0, ID_STATUS, 7, "Estado/Ajuda")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_TEST_GEOFENCE -> { testGeofenceAtCenter(); true }
                ID_UNDO -> { if (polygonEditor?.undo() != true) Toast.makeText(this, "Nada para desfazer", Toast.LENGTH_SHORT).show(); true }
                ID_REDO -> { if (polygonEditor?.redo() != true) Toast.makeText(this, "Nada para refazer", Toast.LENGTH_SHORT).show(); true }
                ID_CLEAR -> { polygonEditor?.clear(); Toast.makeText(this, "Editor limpo", Toast.LENGTH_SHORT).show(); true }
                ID_TOGGLE_MODE -> { toggleEditorMode(); true }
                ID_FINISH -> { polygonEditor?.finalizeIfPossible(); true }
                ID_STATUS -> { showStatusDialog(); true }
                else -> false
            }
        }
        pm.show()
    }

    private fun toggleEditorMode() {
        polygonEditor?.let { editor ->
            editor.mode = if (editor.mode == PolygonEditorOverlay.Mode.PONTOS)
                PolygonEditorOverlay.Mode.DESENHO_LIVRE else PolygonEditorOverlay.Mode.PONTOS
            Toast.makeText(this,
                "Modo: ${if (editor.mode == PolygonEditorOverlay.Mode.PONTOS) "Pontos" else "Desenho livre"}",
                Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Hit-test ----------
    private fun pickZoneAt(lat: Double, lon: Double): Zone? {
        val zones = ZoneRepository.list().filter { it.points.size >= 3 }
        for (z in zones.asReversed()) if (containsPoint(lat, lon, z.points)) return z
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

    // ---------- Status ----------
    private fun testGeofenceAtCenter() {
        val tgt = gmap?.cameraPosition?.target ?: return
        val msg = ZoneRuntime.firstZoneMatch(tgt.latitude, tgt.longitude)?.let {
            "Centro: dentro de \"${it.name}\" (${it.type})"
        } ?: "Centro: fora de zonas"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        ZoneRuntime.updatePosition(tgt.latitude, tgt.longitude)
    }

    private fun showStatusDialog() {
        val cur = gmap?.cameraPosition?.target
        val z = cur?.let { ZoneRuntime.firstZoneMatch(it.latitude, it.longitude) }
        val geofenceTxt = z?.let { "Geofence: ${it.name} (${it.type})" } ?: "Geofence: fora"
        val editor = polygonEditor
        val text = buildString {
            append(geofenceTxt).append('\n')
            append("Zonas ativas: ")
                .append(ZoneRepository.list().count { it.active })
                .append(" / ").append(ZoneRepository.size()).append('\n')
            append("• Modo atual: ").append(if (editor?.mode == PolygonEditorOverlay.Mode.PONTOS) "Pontos" else "Desenho livre").append('\n')
            append("• PONTOS: tocar no mapa adiciona ponto; tocar no primeiro ponto (com 3+) conclui.\n")
            append("• DESENHO LIVRE: arrastar desenha; ao levantar conclui.\n")
            append("• Long-press numa zona para editar.\n")
            append("• Botões: Zonas (drawer) | Guardar | ⋮ opções")
        }
        AlertDialog.Builder(this)
            .setTitle("Editor de Zonas")
            .setMessage(text)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    // ---------- Helpers ----------
    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sim") { d, _ -> d.dismiss(); onYes() }
            .setNegativeButton("Não") { d, _ -> d.dismiss() }
            .show()
    }

    private fun prompt(title: String, current: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { setText(current); setSelection(current.length) }
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
        redrawZones()
    }

    private fun enterEditMode(z: Zone) {
        // desativar a zona enquanto editas
        z.active = false; ZoneRepository.update(z)

        // cópia defensiva dos pontos
        val safePts = z.points.map { GeoPoint(it.latitude, it.longitude) }
        polygonEditor?.mode = PolygonEditorOverlay.Mode.PONTOS
        polygonEditor?.setPolygon(safePts, z.type)

        editingZone = z.copy(points = safePts.toMutableList())
        Toast.makeText(this, "Editar: ${z.name} — arrasta vértices; toca no 1.º ponto para concluir.", Toast.LENGTH_LONG).show()
        refreshZonesUi()
        drawer.closeDrawer(GravityCompat.START)
    }

    private fun refreshZonesUi() { zonesAdapter.submit(ZoneRepository.list()) }
    private fun redrawZones() { if (this::zonesRender.isInitialized && gmap != null) zonesRender.renderAll(ZoneRepository.list()) }

    override fun onPause() {
        super.onPause()
        try { if (ZoneRepository.hasPendingWrites()) ZoneRepository.save() } catch (_: Throwable) {}
    }
    override fun onDestroy() {
        super.onDestroy()
        ZoneRepository.removeListener(repoListener)
        if (this::zonesRender.isInitialized) zonesRender.clear()
        polygonEditor?.dispose()
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    companion object {
        private const val ID_TEST_GEOFENCE = 201
        private const val ID_UNDO = 202
        private const val ID_REDO = 203
        private const val ID_CLEAR = 204
        private const val ID_TOGGLE_MODE = 206
        private const val ID_FINISH = 207
        private const val ID_STATUS = 205

        fun start(context: Context) {
            context.startActivity(Intent(context, MapEditorActivity::class.java))
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
