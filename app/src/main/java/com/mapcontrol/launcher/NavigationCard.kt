package com.mapcontrol.launcher

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapcontrol.R

/**
 * "Nereye?" kartı (Yandex Haritalar): arama hapı ile Ev / İş düğmeleri.
 *  - Hap: Yandex Haritalar'ı arama için açar.
 *  - Ev / İş kısa dokunma: kayıtlı konum varsa rota başlar, yoksa "basılı tut" ipucu çıkar.
 *  - Ev / İş uzun basma: GPS'in o anki konumu Ev / İş olarak kaydedilir (profile özel).
 *
 * Yerleşim tek kez kurulur; [applySpan] yalnızca yönü değiştirir: genişlik >= 4 sütunsa tek satır
 * (hap | Ev | İş), daha darsa üst üste (hap, altında Ev | İş). Zamanlayıcı ya da sürekli dinleyici yok;
 * GPS yalnızca uzun basmada tek seferlik ve zaman aşımlıdır.
 */
class NavigationCard(private val context: Context) : SizedWidget {

    private val store = NavShortcutStore(context)
    private val main = Handler(Looper.getMainLooper())

    private val homeIcon = icon(context, R.drawable.ic_mdi_home, ICON_DP)
    private val workIcon = icon(context, R.drawable.ic_mdi_briefcase, ICON_DP)

    private val pill = searchPill()
    private val homeButton = placeButton(NavPlace.HOME, homeIcon, R.string.launcher_nav_home)
    private val workButton = placeButton(NavPlace.WORK, workIcon, R.string.launcher_nav_work)

    private val placeRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(homeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(gap(context, GAP_DP))
        addView(workButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(pill)
        addView(gap(context, GAP_DP, vertical = false))
        addView(placeRow)
    }

    override val view: View = card(context, paddingDp = LauncherTheme.CARD_PAD_DP).apply {
        addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    init {
        applySpan(Span(4, 2))
        refreshTints()
    }

    /** Genişlik >= 4: tek satır (hap iki pay, Ev ve İş birer pay). Daha dar: hap üstte, Ev | İş altta. */
    override fun applySpan(span: Span) {
        val row = span.w >= WIDE_MIN_COLS
        root.orientation = if (row) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val spacer = root.getChildAt(1)
        spacer.layoutParams = if (row) LinearLayout.LayoutParams(context.dp(GAP_DP), 1) else LinearLayout.LayoutParams(1, context.dp(GAP_DP))
        if (row) {
            pill.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            placeRow.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        } else {
            pill.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            placeRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val p = context.dp(if (row) LauncherTheme.CARD_PAD_DP else LauncherTheme.CARD_PAD_COMPACT_DP)
        view.setPadding(p, p, p, p)
    }

    /** Arama hapı: ikon + "Nereye?"; dokununca Yandex Haritalar arama için açılır. */
    private fun searchPill(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = rounded(context, LauncherTheme.GROUND, 20f)
        val pad = context.dp(12)
        setPadding(pad, 0, pad, 0)
        addView(icon(context, R.drawable.ic_mdi_magnify, ICON_DP, LauncherTheme.TEXT_SECONDARY))
        addView(gap(context, 12))
        addView(text(context, VALUE_SP, LauncherTheme.TEXT_SECONDARY, medium = true).apply { setText(R.string.launcher_nav_title) })
        contentDescription = context.getString(R.string.launcher_nav_title)
        isClickable = true
        isFocusable = true
        setOnClickListener { openSearch() }
        pressFeedback()
    }

    /** Ev / İş düğmesi: üstte ikon, altında ad, ortalı. */
    private fun placeButton(kind: NavPlace, iconView: ImageView, @StringRes nameRes: Int): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(context, LauncherTheme.CONTROL, 20f)
            val pad = context.dp(8)
            setPadding(pad, pad, pad, pad)
            addView(iconView)
            addView(gap(context, 4, vertical = true))
            addView(text(context, VALUE_SP, medium = true).apply {
                setText(nameRes)
                gravity = Gravity.CENTER
            })
            contentDescription = context.getString(nameRes)
            isClickable = true
            isFocusable = true
            setOnClickListener { onPlaceTap(kind) }
            setOnLongClickListener {
                saveCurrentLocation(kind)
                true
            }
            pressFeedback()
        }

    /** Kayıtlı yer tam renk, kayıtsız yer soluk ikon: ne kayıtlı olduğu bir bakışta anlaşılır. */
    private fun refreshTints() {
        homeIcon.setColorFilter(if (store.get(NavPlace.HOME) != null) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY)
        workIcon.setColorFilter(if (store.get(NavPlace.WORK) != null) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY)
    }

    private fun onPlaceTap(kind: NavPlace) {
        val point = store.get(kind)
        if (point == null) toast(R.string.launcher_nav_hold_to_save) else route(point)
    }

    private fun installed(pkg: String): Boolean = context.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun openSearch() {
        val attempts = NavRoute.searchAttempts(::installed).map { toIntent(it) }.toMutableList()
        context.packageManager.getLaunchIntentForPackage(NavRoute.YANDEX_MAPS)?.let { attempts += it }
        launchFirst(attempts)
    }

    private fun route(point: NavPoint) = launchFirst(NavRoute.routeAttempts(point, ::installed).map { toIntent(it) })

    private fun toIntent(l: NavLaunch): Intent {
        val i = if (l.uri != null) Intent(l.action, Uri.parse(l.uri)) else Intent(l.action)
        if (l.pkg.isNotEmpty()) i.setPackage(l.pkg)
        for ((k, v) in l.extras) i.putExtra(k, v)
        return i
    }

    /** İlk başlatılabilen intent kazanır; hiçbiri çalışmazsa "uygulama yok" uyarısı. */
    private fun launchFirst(intents: List<Intent>) {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // sıradaki
            } catch (e: SecurityException) {
                // sıradaki
            }
        }
        toast(R.string.launcher_nav_no_app)
    }

    // ---- Konum kaydı ----

    private var locListener: LocationListener? = null
    private var timeout: Runnable? = null

    /** Uzun basma: izin yoksa ister; varsa son bilinen GPS konumunu, yoksa tek seferlik GPS'i kullanır. */
    private fun saveCurrentLocation(kind: NavPlace) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            toast(R.string.launcher_nav_perm_denied)
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            toast(R.string.launcher_nav_no_location)
            return
        }
        try {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) {
                commit(kind, last)
                return
            }
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                toast(R.string.launcher_nav_no_location)
                return
            }
            cancelLocation()
            toast(R.string.launcher_nav_locating)
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    cancelLocation()
                    commit(kind, location)
                }
                @Deprecated("Eski API'ler için gerekli")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locListener = l
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, l, Looper.getMainLooper())
            val t = Runnable {
                cancelLocation()
                toast(R.string.launcher_nav_no_location)
            }
            timeout = t
            main.postDelayed(t, FIX_TIMEOUT_MS)
        } catch (e: SecurityException) {
            toast(R.string.launcher_nav_perm_denied)
        }
    }

    private fun commit(kind: NavPlace, loc: Location) {
        store.set(kind, NavPoint(loc.latitude, loc.longitude))
        refreshTints()
        toast(if (kind == NavPlace.HOME) R.string.launcher_nav_saved_home else R.string.launcher_nav_saved_work)
    }

    /** Bekleyen tek seferlik GPS isteğini ve zaman aşımını temizler. */
    private fun cancelLocation() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        val l = locListener ?: return
        locListener = null
        try {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(l)
        } catch (e: SecurityException) {
            // izin geri alınmış; yapılacak bir şey yok
        }
    }

    private fun toast(@StringRes res: Int) = Toast.makeText(context, res, Toast.LENGTH_SHORT).show()

    override fun onVisible() = refreshTints()

    override fun onHidden() = cancelLocation()

    override fun release() = cancelLocation()

    private companion object {
        const val WIDE_MIN_COLS = 4
        const val ICON_DP = 32
        const val VALUE_SP = 24f
        const val GAP_DP = 12
        const val REQ_LOCATION = 4107
        const val FIX_TIMEOUT_MS = 20_000L
    }
}
