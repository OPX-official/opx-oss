package com.opx.oss;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** Dashboard: the runtime card plus module tiles, like Stryker's home screen. */
public class HomeFragment extends Fragment implements OpxService.Listener {

    private TextView runtimeValue;
    private TextView runtimeBody;
    private TextView runtimeChip;

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

        view.findViewById(R.id.home_module_wifi).setOnClickListener(v -> open(R.id.nav_wifi));
        view.findViewById(R.id.home_module_logs).setOnClickListener(v -> open(R.id.nav_logs));
        applyState();
    }

    @Override
    public void onResume() {
        super.onResume();
        OpxService service = OpxService.get();
        if (service != null) service.setListener(this);
        applyState();
    }

    @Override
    public void onPause() {
        super.onPause();
        OpxService service = OpxService.get();
        if (service != null) service.clearListener(this);
    }

    private void applyState() {
        if (!isAdded() || runtimeValue == null) return;
        OpxService service = OpxService.get();
        int state = service == null ? OpxService.STATE_STARTING : service.getState();
        switch (state) {
            case OpxService.STATE_READY:
                runtimeValue.setText(R.string.home_runtime_ready);
                runtimeBody.setText(getString(R.string.home_runtime_path, OpxRuntime.PREFIX_GUEST));
                runtimeChip.setText(R.string.wifi_state_ready);
                break;
            case OpxService.STATE_FAILED:
                runtimeValue.setText(R.string.wifi_state_failed);
                runtimeBody.setText(getString(R.string.wifi_usb_error,
                        service == null ? "" : String.valueOf(service.getError())));
                runtimeChip.setText("!");
                break;
            case OpxService.STATE_INSTALLING:
                runtimeValue.setText(R.string.notification_state_installing);
                runtimeBody.setText(R.string.home_runtime_body);
                runtimeChip.setText("…");
                break;
            default:
                runtimeValue.setText(R.string.notification_state_starting);
                runtimeBody.setText(R.string.home_runtime_body);
                runtimeChip.setText("…");
                break;
        }
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

    @Override
    public void onEngineExited() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(this::applyState);
    }
}
