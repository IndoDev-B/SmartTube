package com.liskovsoft.smartyoutubetv2.common.tvmode;

import android.app.admin.DeviceAdminReceiver;

/**
 * Lets the sleep timer put the TV in standby. Android only allows that to an app the user has
 * made a device admin, and this app asks for nothing but the "lock the screen" right.
 */
public class TvModeAdminReceiver extends DeviceAdminReceiver {
}
