package com.mixtli.libros;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Mixtli Libros: envía libros (EPUB) al Xteink con firmware Mixtli por Bluetooth LE.
 *
 * En el Xteink: Inicio → "Recibir por Bluetooth". Aquí: compartir un EPUB hacia
 * "Mixtli Libros" o tocar "Agregar libros". El protocolo está documentado en
 * xteink/crosspoint/src/activities/network/BleReceiveActivity.h.
 */
public class MainActivity extends Activity {
    static final UUID SERVICE = UUID.fromString("8f2c1000-6b1e-4d3a-9b7e-4d6978746c69");
    static final UUID CTRL = UUID.fromString("8f2c1001-6b1e-4d3a-9b7e-4d6978746c69");
    static final UUID DATA = UUID.fromString("8f2c1002-6b1e-4d3a-9b7e-4d6978746c69");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final int REQ_PERMS = 1, REQ_PICK = 2, REQ_BT_ON = 3;
    static final int MAX_IN_FLIGHT = 8 * 1024;   // debe ser menor al búfer del Xteink (16 KB)
    static final long SCAN_TIMEOUT_MS = 15000;
    static final long STALL_TIMEOUT_MS = 20000;

    // Paleta
    static final int C_BG = 0xFFF5F2EB, C_CARD = 0xFFFFFFFF, C_PRIMARY = 0xFF2E5E4E, C_PRIMARY_SOFT = 0xFFDCEAE4,
            C_ACCENT = 0xFFF2C14E, C_TEXT = 0xFF1F2A26, C_MUTED = 0xFF6B7A74, C_OK = 0xFF2E7D32,
            C_ERR = 0xFFC62828, C_LINE = 0xFFE6E1D6;

    final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ Libros
    enum St { PENDING, SENDING, DONE, ERROR }

    static class Book {
        Uri uri; String name; long size; St st = St.PENDING; String note = ""; int pct = 0;
        Book(Uri u, String n, long s) { uri = u; name = n; size = s; }
    }

    final List<Book> books = new ArrayList<>();
    int sentOk = 0, sentFail = 0;

    // ------------------------------------------------------------ BLE
    BluetoothAdapter adapter;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic ctrlChr, dataChr;
    boolean busy = false, scanning = false;
    int chunk = 20;

    Book current;
    byte[] payload;
    int sent = 0, acked = 0;
    boolean waitingWriteCb = false, sentEnd = false;
    long lastProgressAt = 0;

    // ------------------------------------------------------------ Vistas
    ImageView statusIcon;
    TextView statusTitle, statusText;
    ProgressBar statusSpinner;
    LinearLayout list;
    View emptyState;
    TextView sendBtnText, clearBtn;
    LinearLayout sendBtn, addBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(C_PRIMARY);
        getWindow().setNavigationBarColor(C_BG);
        buildUi();
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        setStatus(St.PENDING, "Listo", "Abre \"Recibir por Bluetooth\" en el Xteink y agrega un libro.");
        handleIntent(getIntent());
        refresh();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeGatt();
    }

    // ================================================================= UI

    int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    ImageView icon(int res, int color, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(color));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(C_CARD, 18));
        c.setElevation(dp(1.5f));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(14));
        c.setLayoutParams(lp);
        return c;
    }

    LinearLayout button(int iconRes, String label, int bg, int fg) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), round(bg, 14), null));
        b.setClickable(true);
        ImageView iv = icon(iconRes, fg, 22);
        b.addView(iv);
        TextView t = text(label, 16, fg, true);
        t.setSingleLine(true);
        t.setPadding(dp(10), 0, 0, 0);
        b.addView(t);
        return b;
    }

    void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(C_BG);

        // ---- Encabezado
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(C_PRIMARY);
        header.setPadding(dp(20), dp(22), dp(20), dp(26));
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(round(0x33FFFFFF, 16));
        ImageView reader = new ImageView(this);
        reader.setImageResource(R.drawable.ic_reader);
        reader.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        badge.addView(reader, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        header.addView(badge, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        titles.addView(text("Mixtli Libros", 24, Color.WHITE, true));
        titles.addView(text("Envía libros a tu Xteink por Bluetooth", 14, 0xCCFFFFFF, false));
        header.addView(titles);
        page.addView(header);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(body);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- Estado de conexión
        LinearLayout statusCard = card();
        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout sbadge = new FrameLayout(this);
        sbadge.setBackground(round(C_PRIMARY_SOFT, 22));
        statusIcon = new ImageView(this);
        statusIcon.setImageResource(R.drawable.ic_bluetooth);
        statusIcon.setImageTintList(ColorStateList.valueOf(C_PRIMARY));
        sbadge.addView(statusIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        statusSpinner = new ProgressBar(this);
        statusSpinner.setIndeterminateTintList(ColorStateList.valueOf(C_PRIMARY));
        statusSpinner.setVisibility(View.GONE);
        sbadge.addView(statusSpinner, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        srow.addView(sbadge, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout stexts = new LinearLayout(this);
        stexts.setOrientation(LinearLayout.VERTICAL);
        stexts.setPadding(dp(14), 0, 0, 0);
        statusTitle = text("", 17, C_TEXT, true);
        statusText = text("", 14, C_MUTED, false);
        stexts.addView(statusTitle);
        stexts.addView(statusText);
        srow.addView(stexts, new LinearLayout.LayoutParams(0, -2, 1));
        statusCard.addView(srow);
        body.addView(statusCard);

        // ---- Lista de libros
        LinearLayout listCard = card();
        LinearLayout lhead = new LinearLayout(this);
        lhead.setOrientation(LinearLayout.HORIZONTAL);
        lhead.setGravity(Gravity.CENTER_VERTICAL);
        lhead.addView(text("Libros", 17, C_TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        clearBtn = text("Limpiar lista", 14, C_PRIMARY, true);
        clearBtn.setPadding(dp(8), dp(4), 0, dp(4));
        clearBtn.setOnClickListener(v -> { if (!busy) { books.clear(); refresh(); } });
        lhead.addView(clearBtn);
        listCard.addView(lhead);

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(0, dp(22), 0, dp(14));
        ImageView ebook = icon(R.drawable.ic_book, C_LINE, 54);
        empty.addView(ebook);
        TextView et = text("Aún no hay libros", 16, C_MUTED, true);
        et.setPadding(0, dp(8), 0, dp(2));
        empty.addView(et);
        TextView es = text("Toca \"Agregar\" o, desde cualquier app,\nCompartir → Mixtli Libros", 13, C_MUTED, false);
        es.setGravity(Gravity.CENTER);
        empty.addView(es);
        emptyState = empty;
        listCard.addView(empty);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(list);
        body.addView(listCard);

        // ---- Cómo funciona
        LinearLayout help = card();
        LinearLayout hhead = new LinearLayout(this);
        hhead.setGravity(Gravity.CENTER_VERTICAL);
        hhead.addView(icon(R.drawable.ic_info, C_PRIMARY, 20));
        TextView ht = text("Cómo enviar", 15, C_TEXT, true);
        ht.setPadding(dp(8), 0, 0, 0);
        hhead.addView(ht);
        help.addView(hhead);
        help.addView(step(1, "En el Xteink, en Inicio, abre \"Recibir por Bluetooth\"."));
        help.addView(step(2, "Aquí toca \"Agregar\" y elige tus EPUB."));
        help.addView(step(3, "Se envían solos. Al terminar aparecen en tu biblioteca."));
        body.addView(help);

        // ---- Botones fijos abajo
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(16), dp(10), dp(16), dp(18));
        bottom.setBackgroundColor(C_BG);
        addBtn = button(R.drawable.ic_add, "Agregar", C_PRIMARY_SOFT, C_PRIMARY);
        addBtn.setOnClickListener(v -> pickBooks());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1);
        l1.setMargins(0, 0, dp(6), 0);
        bottom.addView(addBtn, l1);
        sendBtn = button(R.drawable.ic_send, "Enviar", C_PRIMARY, Color.WHITE);
        sendBtnText = (TextView) sendBtn.getChildAt(1);
        sendBtn.setOnClickListener(v -> startSending());
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1);
        l2.setMargins(dp(6), 0, 0, 0);
        bottom.addView(sendBtn, l2);
        page.addView(bottom);

        setContentView(page);
    }

    View step(int n, String s) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, 0);
        TextView num = text(String.valueOf(n), 13, C_TEXT, true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(round(C_ACCENT, 12));
        r.addView(num, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = text(s, 14, C_TEXT, false);
        t.setPadding(dp(10), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }

    View bookRow(Book k) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(4));

        int iconRes, tint, bg;
        switch (k.st) {
            case SENDING: iconRes = R.drawable.ic_upload; tint = C_PRIMARY; bg = C_PRIMARY_SOFT; break;
            case DONE: iconRes = R.drawable.ic_check; tint = C_OK; bg = 0xFFE3F1E4; break;
            case ERROR: iconRes = R.drawable.ic_error; tint = C_ERR; bg = 0xFFFBE4E4; break;
            default: iconRes = R.drawable.ic_book; tint = C_MUTED; bg = 0xFFF0EDE6; break;
        }
        FrameLayout b = new FrameLayout(this);
        b.setBackground(round(bg, 12));
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        b.addView(iv, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        r.addView(b, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(k.name.replaceAll("\\.epub$", ""), 15, C_TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(name);
        String sub;
        int subColor = C_MUTED;
        switch (k.st) {
            case SENDING: sub = k.note.isEmpty() ? "Enviando… " + k.pct + "%" : k.note; subColor = C_PRIMARY; break;
            case DONE: sub = "En el Xteink"; subColor = C_OK; break;
            case ERROR: sub = k.note; subColor = C_ERR; break;
            default: sub = "En espera · " + sizeText(k.size); break;
        }
        tx.addView(text(sub, 13, subColor, false));
        if (k.st == St.SENDING) {
            ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            p.setMax(100);
            p.setProgress(k.pct);
            p.setProgressTintList(ColorStateList.valueOf(C_PRIMARY));
            p.setProgressBackgroundTintList(ColorStateList.valueOf(C_PRIMARY_SOFT));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(8));
            pl.setMargins(0, dp(6), 0, 0);
            tx.addView(p, pl);
        }
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));

        if (!busy && k.st != St.SENDING) {
            ImageView x = icon(R.drawable.ic_close, C_MUTED, 20);
            x.setPadding(dp(4), dp(4), dp(4), dp(4));
            x.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
            x.setContentDescription("Quitar");
            x.setOnClickListener(v -> { books.remove(k); refresh(); });
            r.addView(x);
        }
        return r;
    }

    static String sizeText(long s) {
        if (s < 0) return "";
        if (s < 1024 * 1024) return Math.max(1, s / 1024) + " KB";
        return String.format("%.1f MB", s / (1024.0 * 1024.0));
    }

    int pendingCount() {
        int n = 0;
        for (Book k : books) if (k.st == St.PENDING || k.st == St.ERROR) n++;
        return n;
    }

    void refresh() {
        list.removeAllViews();
        for (Book k : books) list.addView(bookRow(k));
        emptyState.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
        clearBtn.setVisibility(!busy && !books.isEmpty() ? View.VISIBLE : View.GONE);
        int p = pendingCount();
        boolean canSend = !busy && p > 0;
        sendBtn.setEnabled(canSend);
        sendBtn.setAlpha(canSend ? 1f : 0.45f);
        addBtn.setEnabled(!busy);
        addBtn.setAlpha(busy ? 0.45f : 1f);
        sendBtnText.setText(busy ? "Enviando…" : p > 1 ? "Enviar " + p : "Enviar");
    }

    void setStatus(St kind, String title, String msg) {
        statusTitle.setText(title);
        statusText.setText(msg);
        boolean working = kind == St.SENDING;
        statusSpinner.setVisibility(working ? View.VISIBLE : View.GONE);
        int res = kind == St.DONE ? R.drawable.ic_check : kind == St.ERROR ? R.drawable.ic_error : R.drawable.ic_bluetooth;
        int tint = kind == St.DONE ? C_OK : kind == St.ERROR ? C_ERR : C_PRIMARY;
        statusIcon.setImageResource(res);
        statusIcon.setImageTintList(ColorStateList.valueOf(tint));
    }

    // ============================================================ Entrada

    void handleIntent(Intent i) {
        if (i == null) return;
        List<Uri> uris = new ArrayList<>();
        String a = i.getAction();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else if (Intent.ACTION_VIEW.equals(a) && i.getData() != null) {
            uris.add(i.getData());
        }
        if (uris.isEmpty()) return;
        for (Uri u : uris) addBook(u);
        setIntent(new Intent(Intent.ACTION_MAIN));
        refresh();
        // Llegó por "Compartir": enviar de una vez.
        if (!busy && pendingCount() > 0) startSending();
    }

    void pickBooks() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "application/octet-stream", "text/plain"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) addBook(data.getClipData().getItemAt(k).getUri());
            } else if (data.getData() != null) {
                addBook(data.getData());
            }
            refresh();
            if (pendingCount() > 0) startSending();
        } else if (req == REQ_BT_ON) {
            if (res == RESULT_OK) startSending();
            else fail("Bluetooth está apagado en el teléfono");
        }
    }

    void addBook(Uri u) {
        String name = null;
        long size = -1;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (Exception ignored) { }
        if (name == null) name = u.getLastPathSegment();
        if (name == null) name = "libro.epub";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String lower = name.toLowerCase();
        if (!lower.endsWith(".epub") && !lower.endsWith(".txt") && !lower.endsWith(".xtc") && !lower.endsWith(".md")) {
            name = name + ".epub";
        }
        for (Book k : books) if (k.uri.equals(u)) { if (k.st == St.ERROR) k.st = St.PENDING; return; }
        books.add(new Book(u, name, size));
    }

    // ===================================================== Permisos / BT

    boolean ensurePermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (need.isEmpty()) return true;
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        for (int g : grants) if (g != PackageManager.PERMISSION_GRANTED) { fail("Falta el permiso \"Dispositivos cercanos\""); return; }
        startSending();
    }

    // ========================================================= Conexión

    void startSending() {
        if (busy || pendingCount() == 0) return;
        if (adapter == null) { fail("Este teléfono no tiene Bluetooth"); return; }
        if (!ensurePermissions()) return;
        if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ON);
            return;
        }
        for (Book k : books) if (k.st == St.ERROR) { k.st = St.PENDING; k.note = ""; }
        busy = true;
        sentOk = sentFail = 0;
        refresh();
        if (gatt != null && ctrlChr != null) { nextBook(); return; }
        scan();
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            if (!scanning) return;
            BluetoothDevice d = r.getDevice();
            ui.post(() -> {
                if (!scanning) return;
                stopScan();
                setStatus(St.SENDING, "Conectando…", "Encontré tu Xteink");
                connect(d);
            });
        }
        @Override public void onScanFailed(int code) {
            ui.post(() -> fail("No se pudo buscar (error " + code + ")"));
        }
    };

    void scan() {
        setStatus(St.SENDING, "Buscando el Xteink…", "Debe estar en \"Recibir por Bluetooth\"");
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { fail("Bluetooth no disponible"); return; }
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        try {
            scanner.startScan(Collections.singletonList(f), s, scanCb);
        } catch (SecurityException e) { scanning = false; fail("Falta el permiso de Bluetooth"); return; }
        ui.postDelayed(() -> {
            if (scanning) {
                stopScan();
                fail("No encontré el Xteink. Abre \"Recibir por Bluetooth\" en el lector y vuelve a tocar Enviar.");
            }
        }, SCAN_TIMEOUT_MS);
    }

    void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
    }

    void connect(BluetoothDevice d) {
        try {
            gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) { fail("Falta el permiso de Bluetooth"); }
    }

    void closeGatt() {
        stopScan();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (SecurityException ignored) { }
        }
        gatt = null;
        ctrlChr = dataChr = null;
    }

    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            ui.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        g.requestMtu(247);
                    } catch (SecurityException e) { fail("Falta el permiso de Bluetooth"); }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean wasBusy = busy;
                    closeGatt();
                    if (wasBusy) fail("Se perdió la conexión con el Xteink");
                }
            });
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
            ui.post(() -> {
                chunk = Math.max(20, mtu - 3);
                try { g.discoverServices(); } catch (SecurityException e) { fail("Falta el permiso de Bluetooth"); }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            ui.post(() -> {
                BluetoothGattService s = g.getService(SERVICE);
                if (s == null) { fail("Ese dispositivo no es un Xteink Mixtli"); return; }
                ctrlChr = s.getCharacteristic(CTRL);
                dataChr = s.getCharacteristic(DATA);
                if (ctrlChr == null || dataChr == null) { fail("El firmware del Xteink no es compatible"); return; }
                try {
                    g.setCharacteristicNotification(ctrlChr, true);
                    BluetoothGattDescriptor d = ctrlChr.getDescriptor(CCCD);
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(d);
                    }
                } catch (SecurityException e) { fail("Falta el permiso de Bluetooth"); }
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            ui.post(() -> nextBook());
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            if (!DATA.equals(c.getUuid())) return;
            ui.post(() -> {
                waitingWriteCb = false;
                if (st != BluetoothGatt.GATT_SUCCESS) { fail("Error al enviar (" + st + ")"); return; }
                pump();
            });
        }

        // Android 13+
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            String msg = new String(value, StandardCharsets.UTF_8);
            ui.post(() -> onNotify(msg));
        }

        // Android 12 y anteriores
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return;
            String msg = new String(c.getValue(), StandardCharsets.UTF_8);
            ui.post(() -> onNotify(msg));
        }
    };

    // ============================================================= Envío

    void nextBook() {
        current = null;
        for (Book k : books) if (k.st == St.PENDING) { current = k; break; }
        if (current == null) { allDone(); return; }
        current.st = St.SENDING;
        current.pct = 0;
        current.note = "Preparando…";
        setStatus(St.SENDING, "Conectado al Xteink", "Enviando " + (sentOk + sentFail + 1) + " de " + (sentOk + sentFail + pendingCount() + 1));
        refresh();
        try (InputStream in = getContentResolver().openInputStream(current.uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(current.size > 0 ? (int) current.size : 1 << 20);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            payload = bos.toByteArray();
        } catch (Exception e) {
            markCurrent(St.ERROR, "No se pudo leer el archivo");
            nextBook();
            return;
        }
        current.size = payload.length;
        CRC32 crc = new CRC32();
        crc.update(payload);
        sent = acked = 0;
        sentEnd = false;
        waitingWriteCb = false;
        lastProgressAt = System.currentTimeMillis();
        String crcHex = Long.toHexString(crc.getValue());
        String header = "B|" + payload.length + "|" + crcHex + "|" + current.name;
        byte[] hb = header.getBytes(StandardCharsets.UTF_8);
        int dot = current.name.lastIndexOf('.');
        if (hb.length > chunk && dot > 0) {
            // Nombre demasiado largo para un paquete: recortarlo conservando la extensión.
            String ext = current.name.substring(dot);
            String stem = current.name.substring(0, dot);
            while (hb.length > chunk && stem.length() > 8) {
                stem = stem.substring(0, stem.length() - 4);
                header = "B|" + payload.length + "|" + crcHex + "|" + stem + ext;
                hb = header.getBytes(StandardCharsets.UTF_8);
            }
        }
        writeCtrl(header);
        watchdog(current);
    }

    void markCurrent(St st, String note) {
        if (current == null) return;
        current.st = st;
        current.note = note;
        if (st == St.DONE) sentOk++; else if (st == St.ERROR) sentFail++;
        current = null;
        payload = null;
        refresh();
    }

    void watchdog(Book b) {
        ui.postDelayed(() -> {
            if (!busy || current != b) return;
            if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                fail("El Xteink dejó de responder");
            } else {
                watchdog(b);
            }
        }, 3000);
    }

    void writeCtrl(String s) {
        byte[] v = s.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(ctrlChr, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                ctrlChr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                ctrlChr.setValue(v);
                gatt.writeCharacteristic(ctrlChr);
            }
        } catch (SecurityException e) { fail("Falta el permiso de Bluetooth"); }
    }

    /** Envía el siguiente paquete si la ventana lo permite. */
    void pump() {
        if (!busy || payload == null || waitingWriteCb || gatt == null) return;
        if (sent >= payload.length) {
            if (!sentEnd) {
                sentEnd = true;
                writeCtrl("E");
                if (current != null) { current.note = "Guardando en el Xteink…"; refresh(); }
            }
            return;
        }
        if (sent - acked >= MAX_IN_FLIGHT) return;  // esperar el "A|n" del Xteink
        int len = Math.min(chunk, payload.length - sent);
        byte[] part = new byte[len];
        System.arraycopy(payload, sent, part, 0, len);
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int r = gatt.writeCharacteristic(dataChr, part, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                ok = r == BluetoothStatusCodes.SUCCESS;
            } else {
                dataChr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                dataChr.setValue(part);
                ok = gatt.writeCharacteristic(dataChr);
            }
        } catch (SecurityException e) { fail("Falta el permiso de Bluetooth"); return; }
        if (!ok) { ui.postDelayed(this::pump, 15); return; }  // pila ocupada: reintentar
        sent += len;
        waitingWriteCb = true;
    }

    long lastRowUpdate = 0;

    void onNotify(String msg) {
        if (!busy) return;
        lastProgressAt = System.currentTimeMillis();
        if (msg.equals("R")) {
            if (current != null) { current.note = ""; refresh(); }
            pump();
        } else if (msg.startsWith("A|")) {
            try { acked = Integer.parseInt(msg.substring(2)); } catch (NumberFormatException ignored) { }
            if (current != null && payload != null && payload.length > 0) {
                current.pct = (int) (100L * acked / payload.length);
                if (lastProgressAt - lastRowUpdate > 400) { lastRowUpdate = lastProgressAt; refresh(); }
            }
            pump();
        } else if (msg.startsWith("OK|")) {
            markCurrent(St.DONE, "");
            nextBook();
        } else if (msg.startsWith("ER|")) {
            markCurrent(St.ERROR, msg.substring(3));
            nextBook();
        }
    }

    void allDone() {
        busy = false;
        closeGatt();
        refresh();
        if (sentFail == 0 && sentOk > 0) {
            setStatus(St.DONE, sentOk == 1 ? "¡Libro enviado!" : "¡" + sentOk + " libros enviados!",
                    "Ya están en la biblioteca del Xteink.");
        } else if (sentOk + sentFail > 0) {
            setStatus(St.ERROR, sentOk + " enviados, " + sentFail + " con error", "Toca Enviar para reintentar los que fallaron.");
        }
    }

    void fail(String why) {
        if (busy && gatt != null && ctrlChr != null) writeCtrl("C");
        busy = false;
        if (current != null) { current.st = St.ERROR; current.note = why; }
        current = null;
        payload = null;
        stopScan();
        ui.postDelayed(this::closeGatt, 300);
        setStatus(St.ERROR, "No se pudo enviar", why);
        refresh();
    }
}
