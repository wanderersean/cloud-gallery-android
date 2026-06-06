package org.fossify.gallery.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.gallery.R
import org.fossify.gallery.cloud.CloudUploadManager
import org.fossify.gallery.databinding.ItemUploadTaskBinding

class UploadTaskAdapter(
    private var tasks: List<CloudUploadManager.UploadTask>,
    private val onCancelClick: (String) -> Unit
) : RecyclerView.Adapter<UploadTaskAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemUploadTaskBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUploadTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val task = tasks[position]
        val context = holder.binding.root.context

        holder.binding.apply {
            taskFilename.text = task.filename
            taskProgress.progress = task.progress

            Glide.with(context)
                .load(task.path)
                .centerCrop()
                .placeholder(R.drawable.ic_cloud_upload_vector)
                .into(taskThumbnail)

            when (task.status) {
                CloudUploadManager.UploadStatus.PENDING -> {
                    taskStatus.text = context.getString(R.string.cloud_status_pending)
                    taskStatus.setTextColor(context.getColor(android.R.color.darker_gray))
                    taskCancelBtn.visibility = View.VISIBLE
                }
                CloudUploadManager.UploadStatus.UPLOADING -> {
                    taskStatus.text = String.format(context.getString(R.string.cloud_status_uploading), task.progress)
                    taskStatus.setTextColor(context.getColor(android.R.color.holo_blue_dark))
                    taskCancelBtn.visibility = View.VISIBLE
                }
                CloudUploadManager.UploadStatus.SUCCESS -> {
                    taskStatus.text = context.getString(R.string.cloud_status_success)
                    taskStatus.setTextColor(context.getColor(android.R.color.holo_green_dark))
                    taskCancelBtn.visibility = View.GONE
                    taskProgress.progress = 100
                }
                CloudUploadManager.UploadStatus.FAILED -> {
                    taskStatus.text = "${context.getString(R.string.cloud_status_failed)}: ${task.errorMessage}"
                    taskStatus.setTextColor(context.getColor(android.R.color.holo_red_dark))
                    taskCancelBtn.visibility = View.GONE
                }
                CloudUploadManager.UploadStatus.CANCELLED -> {
                    taskStatus.text = context.getString(R.string.cloud_status_cancelled)
                    taskStatus.setTextColor(context.getColor(android.R.color.darker_gray))
                    taskCancelBtn.visibility = View.GONE
                }
            }

            taskCancelBtn.setOnClickListener {
                onCancelClick(task.path)
            }
        }
    }

    override fun getItemCount() = tasks.size

    fun updateTasks(newTasks: List<CloudUploadManager.UploadTask>) {
        tasks = newTasks
        notifyDataSetChanged()
    }
}
