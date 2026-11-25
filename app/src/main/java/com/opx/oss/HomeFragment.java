package com.opx.oss;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.airbnb.lottie.LottieAnimationView;

/** Dashboard: the runtime card plus module tiles, like Stryker's home screen. */
public class HomeFragment extends Fragment implements OpxService.Listener {

    private TextView runtimeValue;
    private TextView runtimeBody;
    private TextView runtimeChip;
    private LottieAnimationView engineAnim;
    private int animRes = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        runtimeValue = view.findViewById(R.id.home_runtime_value);
        runtimeBody = view.findViewById(R.id.home_runtime_body);
        runtimeChip = view.findViewById(R.id.home_runtime_chip);
        engineAnim = view.findViewById(R.id.home_engine_anim);

        view.findViewById(R.id.home_hero_scan).setOnClickListener(v -> open(R.id.nav_wifi));
        view.findViewById(R.id.home_tool_wifi).setOnClickListener(v -> open(R.id.nav_wifi));
        view.findViewById(R.id.home_tool_hs).setOnClickListener(v -> open(R.id.nav_handshakes));
        view.findViewById(R.id.home_tool_creds).setOnClickListener(v -> open(R.id.nav_credentials));
        view.findViewById(R.id.home_tool_logs).setOnClickListener(v -> open(R.id.nav_logs));
        view.findViewById(R.id.home_tool_settings).setOnClickListener(v -> open(R.id.nav_settings));
        view.findViewById(R.id.home_recent_hs).setOnClickListener(v -> open(R.id.nav_handshakes));
        applyState();
    }

    @Override
    public void onResume() {
        super.onResume();
        OpxService.addListener(this);
        applyState();
    }

    @Override
    public void onPause() {
        super.onPause();
        OpxService.removeListener(this);
    }

    private void applyState() {
        if (!isAdded() || runtimeValue == null) return;
        int state = OpxService.getState();
        switch (state) {
            case OpxService.STATE_READY:
                runtimeValue.setText(R.string.home_runtime_ready);
                runtimeBody.setText(getString(R.string.home_runtime_path, OpxRuntime.PREFIX_GUEST));
                runtimeChip.setText(R.string.wifi_state_ready);
                showAnim(R.raw.hello);
                break;
            case OpxService.STATE_FAILED:
                runtimeValue.setText(R.string.wifi_state_failed);
                runtimeBody.setText(getString(R.string.wifi_usb_error,
                        String.valueOf(OpxService.getError())));
                runtimeChip.setText("!");
                showAnim(R.raw.error);
                break;
            case OpxService.STATE_INSTALLING:
                runtimeValue.setText(R.string.notification_state_installing);
                runtimeBody.setText(R.string.home_runtime_body);
                runtimeChip.setText("…");
                showAnim(R.raw.connection);
                break;
            case OpxService.STATE_STOPPED:
                runtimeValue.setText(R.string.engine_stopped);
                runtimeBody.setText(R.string.home_runtime_body);
                runtimeChip.setText("—");
                showAnim(R.raw.nothing);
                break;
            default:
                runtimeValue.setText(R.string.notification_state_starting);
                runtimeBody.setText(R.string.home_runtime_body);
                runtimeChip.setText("…");
                showAnim(R.raw.connection);
                break;
        }
    }

    /**
     * The state animation, exactly where Stryker puts its own: connection while the engine is
     * coming up, the greeting once it is ready, error when it failed and the empty loop when it
     * is stopped. Only swapped when the state really changed, so the loop is not restarted on
     * every resume.
     */
    private void showAnim(int res) {
        if (engineAnim == null || animRes == res) return;
        animRes = res;
        engineAnim.setAnimation(res);
        engineAnim.playAnimation();
    }

    private void open(int itemId) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).open(itemId);
        }
    }

    @Override
    public void onEngineStateChanged() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(this::applyState);
    }
}
