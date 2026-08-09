package com.screensaathi.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.screensaathi.R
import com.screensaathi.device.DeviceApp

/**
 * The home-screen app grid.
 *
 * Backed by whatever DeviceContextProvider actually found — there is no curated
 * list and no demo apps. If an app is not on this grid, PackageManager did not
 * report it as launchable, which is the same evidence the agent reasons from.
 */
class AppGridAdapter(
    private val apps: List<DeviceApp>,
    private val onLaunch: (DeviceApp) -> Unit,
) : RecyclerView.Adapter<AppGridAdapter.AppViewHolder>() {

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
        AppViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        )

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        holder.label.text = app.label

        // loadIcon can throw for an app being uninstalled mid-scroll; a missing
        // icon is not worth crashing the home screen over.
        holder.icon.setImageDrawable(
            runCatching {
                holder.itemView.context.packageManager.getApplicationIcon(app.packageName)
            }.getOrNull()
        )

        // contentDescription, not just the visible label: this grid is read by
        // our own accessibility layer too, and an icon with no description is
        // invisible to the thing that has to point at it.
        holder.itemView.contentDescription = app.label
        holder.itemView.setOnClickListener { onLaunch(app) }
    }
}
