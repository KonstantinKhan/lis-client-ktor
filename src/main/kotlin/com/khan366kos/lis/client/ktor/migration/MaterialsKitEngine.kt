package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Identifier
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import java.util.concurrent.atomic.AtomicInteger

// Общий приём "создать Комплект вспомогательных материалов на родителя, реверсивная связь на
// родителя" — переиспользуется CastingBlanksEngine.kt (литейные заготовки: связи) и
// AuxMaterialsEngine.kt (вспомогательные материалы для Детали). Оба потока создают один и тот же
// тип объекта Loodsman тем же способом; разница между ними только в источнике строк Excel и в
// резолве материалов (с ветвлением по типу или без), поэтому параметризовано голыми строками, а
// не общим объектом настроек.
suspend fun MigrationContext.createMaterialsKit(
    parent: Identifier,
    setTarget: String,
    setState: String,
    setLinkType: String,
    setsCreated: AtomicInteger,
    setLinksCreated: AtomicInteger,
    setFailures: AtomicInteger,
): Int? = try {
    val kitId = loodsmanClient.editObject.create(
        sessionId,
        NewObjectInputDto(
            typeName = setTarget,
            stateName = setState,
            keyAttribute = parent.designation.ifBlank { parent.classifierId.toString() },
            isProject = false,
        )
    ).asInt()
    setsCreated.incrementAndGet()

    // Реверсивная связь: субъект — комплект, объект — родитель (правило связывания в Loodsman для
    // setLinkType сконфигурировано в эту сторону, как "Заготовка для"/"Технологическая ДСЕ для").
    loodsmanClient.editObject.newLink(
        sessionId,
        NewLinkInputDto(
            parentVersionId = kitId,
            childVersionId = parent.loodsmanId,
            linkType = setLinkType,
        )
    )
    setLinksCreated.incrementAndGet()

    kitId
} catch (e: Exception) {
    setFailures.incrementAndGet()
    System.err.println(
        "Комплект вспомогательных материалов: не удалось создать (родитель ${parent.loodsmanId}): ${e.message}"
    )
    (e as? ResponseException)?.let {
        System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
    }
    null
}
