package com.opx.oss;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.google.android.material.navigation.NavigationView;

/** Single-activity host: drawer navigation between WiFi, Home and About (there is no terminal). */
public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private DrawerLayout drawer;
    private NavigationView navigation;
    private int currentItem = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        drawer = findViewById(R.id.drawer_layout);
        navigation = findViewById(R.id.nav_view);
        navigation.setNavigationItemSelectedListener(this);

        // The engine starts with the app, not with the terminal screen: the bundled userland is
        // installed and kept warm so every module (and the WiFi engine later) can use it even if
        // the terminal is never opened.
        OpxService.start(this);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.drawer_open, R.string.drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        if (savedInstanceState == null) {
            select(R.id.nav_wifi);
        }
    }

    private void select(int itemId) {
        if (itemId == currentItem) {
            return;
        }
        currentItem = itemId;
        Fragment target;
        if (itemId == R.id.nav_home) {
            target = new HomeFragment();
        } else if (itemId == R.id.nav_about) {
            target = new AboutFragment();
        } else {
            target = new WifiFragment();
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, target)
                .commit();
        navigation.setCheckedItem(itemId);
        setTitle(navigation.getMenu().findItem(itemId).getTitle());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        select(item.getItemId());
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
    }
}
