package com.kiktor.v2whitelist.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.desktop.ui.theme.*
import java.awt.image.BufferedImage

object QrCodeGenerator {
    fun generateQrImage(text: String, size: Int = 260): ImageBitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    image.setRGB(x, y, if (bitMatrix.get(x, y)) 0x000000 else 0xFFFFFF)
                }
            }
            image.toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun QrCodeDialog(
    profile: ProfileItem,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val uri = remember(profile) {
        UniversalConfigParser.toUri(profile) ?: ""
    }
    val qrBitmap = remember(uri) {
        if (uri.isNotBlank()) QrCodeGenerator.generateQrImage(uri, 260) else null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.width(360.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.remarks.ifBlank { "Конфигурация" },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${profile.configType.name} • ${profile.server}:${profile.serverPort}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // QR Code Container
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("Не удалось сгенерировать QR", color = Color.Black, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Copy URI Button
                Button(
                    onClick = {
                        if (uri.isNotBlank()) {
                            onCopy(uri)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Скопировать ссылку", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
