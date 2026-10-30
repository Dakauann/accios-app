package com.gdreducacional.totemapp.pickup

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gdreducacional.totemapp.ui.theme.AcciosColors

@Composable
fun PickupPanel(
    match: PickupMatch,
    busy: Boolean,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var previewStudent by remember { mutableStateOf<PickupStudent?>(null) }

    Box(
        modifier = Modifier.fillMaxSize().background(AcciosColors.surfaceDeep),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 760.dp)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 12.dp)
        ) {
            Text(
                "Retirada de alunos",
                style = MaterialTheme.typography.headlineMedium,
                color = AcciosColors.textPrimary
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Responsável reconhecido",
                style = MaterialTheme.typography.bodyMedium,
                color = AcciosColors.textTertiary
            )
            Spacer(Modifier.height(3.dp))
            Text(
                match.personName,
                style = MaterialTheme.typography.titleLarge,
                color = AcciosColors.textPrimary
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "Quem está saindo agora?",
                style = MaterialTheme.typography.titleMedium,
                color = AcciosColors.textPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Toque no aluno para selecionar. Toque na foto para ampliar.",
                style = MaterialTheme.typography.bodyMedium,
                color = AcciosColors.textSecondary
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(match.students, key = { it.enrollmentId }) { student ->
                    val checked = student.enrollmentId in match.selectedEnrollmentIds
                    val bitmap = remember(student.photoBase64) {
                        try {
                            student.photoBase64?.let { encoded ->
                                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }
                        } catch (_: Exception) { null }
                    }
                    val cardShape = RoundedCornerShape(16.dp)
                    val photoShape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(cardShape)
                            .background(
                                if (checked) AcciosColors.detecting.copy(alpha = 0.12f)
                                else AcciosColors.surface
                            )
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (checked) AcciosColors.detecting.copy(alpha = 0.58f)
                                    else AcciosColors.glassCardBorder
                                ),
                                cardShape
                            )
                            .clickable(enabled = !busy, role = Role.Checkbox) { onToggle(student.enrollmentId) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Ampliar foto de ${student.name}",
                                modifier = Modifier.size(76.dp)
                                    .clip(photoShape)
                                    .border(BorderStroke(1.dp, AcciosColors.glassBorder), photoShape)
                                    .clickable(
                                        enabled = !busy,
                                        role = Role.Button,
                                        onClickLabel = "Ampliar foto de ${student.name}"
                                    ) { previewStudent = student },
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(76.dp)
                                    .clip(photoShape)
                                    .background(AcciosColors.surfaceDeep),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Sem foto",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AcciosColors.textTertiary
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                student.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = AcciosColors.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                student.className,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AcciosColors.textSecondary
                            )
                        }
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 52.dp)
                ) { Text("Cancelar") }
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Button(
                    onClick = onConfirm,
                    enabled = !busy && match.selectedEnrollmentIds.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 52.dp)
                ) {
                    Text("Confirmar retirada")
                }
            }
        }
    }

    previewStudent?.let { student ->
        val bitmap = remember(student.photoBase64) {
            try {
                student.photoBase64?.let { encoded ->
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            } catch (_: Exception) { null }
        }
        if (bitmap != null) {
            Dialog(
                onDismissRequest = { previewStudent = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f),
                        shape = RoundedCornerShape(20.dp),
                        color = AcciosColors.surface,
                        border = BorderStroke(1.dp, AcciosColors.glassBorder)
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Foto do aluno",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AcciosColors.textTertiary
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        student.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = AcciosColors.textPrimary
                                    )
                                    Text(
                                        student.className,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AcciosColors.textSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { previewStudent = null },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Fechar foto",
                                        tint = AcciosColors.textPrimary
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            val previewShape = RoundedCornerShape(14.dp)
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth()
                                    .clip(previewShape)
                                    .background(AcciosColors.surfaceDeep)
                                    .border(
                                        BorderStroke(1.dp, AcciosColors.glassBorder),
                                        previewShape
                                    )
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Foto ampliada de ${student.name}",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
