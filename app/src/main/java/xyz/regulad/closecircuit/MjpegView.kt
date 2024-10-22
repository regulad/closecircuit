package xyz.regulad.closecircuit

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the starting position of the specified subarray within the array.
 *
 * @param subArray the subarray to search for
 * @param startIndex the index to start the search from
 * @return the index of the first occurrence of the subarray in the array,
 *         or -1 if the subarray is not found
 */
fun ByteArray.indexOf(subArray: ByteArray, startIndex: Int = 0): Int {
    if (subArray.isEmpty()) return -1
    if (startIndex < 0) return -1

    for (i in startIndex..this.size - subArray.size) {
        var found = true
        for (j in subArray.indices) {
            if (this[i + j] != subArray[j]) {
                found = false
                break
            }
        }
        if (found) return i
    }
    return -1
}

@Composable
fun MjpegView(modifier: Modifier = Modifier, url: String) {
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                // Open connection to the MJPEG stream
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()

                // Create a buffered input stream for efficient reading
                val inputStream = BufferedInputStream(connection.inputStream)

                // Create a byte array output stream to accumulate data
                val outputStream = ByteArrayOutputStream()

                // Buffer for reading data
                val buffer = ByteArray(8192)

                // Markers for JPEG start and end
                val startMarker = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
                val endMarker = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

                while (isActive) {
                    // Read data from the input stream
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break // End of stream

                    // Write the read data to the output stream
                    outputStream.write(buffer, 0, bytesRead)

                    // Convert the accumulated data to a byte array
                    val data = outputStream.toByteArray()

                    // Find the start of the JPEG image
                    val startIndex = data.indexOf(startMarker)
                    if (startIndex >= 0) {
                        // Find the end of the JPEG image
                        val endIndex = data.indexOf(endMarker, startIndex)
                        if (endIndex >= 0) {
                            // Extract the JPEG image data
                            val imageData = data.copyOfRange(startIndex, endIndex + endMarker.size)

                            // Decode the JPEG data into a bitmap
                            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)

                            // Convert the bitmap to an ImageBitmap and update the current frame
                            bitmap?.let {
                                currentFrame = it.asImageBitmap()
                            }

                            // Reset the output stream and write any remaining data
                            outputStream.reset()
                            outputStream.write(data, endIndex + endMarker.size, data.size - endIndex - endMarker.size)
                        }
                    }
                }
            } catch (e: Exception) {
                // Handle any exceptions (e.g., network errors)
                e.printStackTrace()
            }
        }
    }

    // Display the current frame
    currentFrame?.let { frame ->
        Image(
            bitmap = frame,
            contentDescription = "MJPEG Stream",
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}
