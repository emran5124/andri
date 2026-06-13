package com.example.viewmodel

import com.example.db.AppDatabase
import com.example.db.PromptTemplate

class PromptManager(private val db: AppDatabase) {

    private val promptTemplateDao = db.promptTemplateDao()

    fun getAllTemplatesFlow() = promptTemplateDao.getAllTemplatesFlow()

    suspend fun getAllTemplates(): List<PromptTemplate> = promptTemplateDao.getAllTemplates()

    suspend fun addPromptTemplate(title: String, prompt: String) {
        val existing = promptTemplateDao.getAllTemplates()
        val order = if (existing.isEmpty()) 0 else (existing.maxOfOrNull { it.priorityOrder } ?: 0) + 1
        promptTemplateDao.insertTemplate(
            PromptTemplate(title = title, promptContent = prompt, priorityOrder = order)
        )
    }

    suspend fun updatePromptTemplate(id: Int, title: String, prompt: String, priorityOrder: Int) {
        promptTemplateDao.updateTemplate(
            PromptTemplate(id = id, title = title, promptContent = prompt, priorityOrder = priorityOrder)
        )
    }

    suspend fun movePromptTemplateUp(template: PromptTemplate) {
        val templates = promptTemplateDao.getAllTemplates()
        val currentIndex = templates.indexOfFirst { it.id == template.id }
        if (currentIndex > 0) {
            val prevTemplate = templates[currentIndex - 1]
            val updatedPrevOrder = template.priorityOrder
            val updatedCurrentOrder = prevTemplate.priorityOrder
            promptTemplateDao.insertTemplate(template.copy(priorityOrder = updatedCurrentOrder))
            promptTemplateDao.insertTemplate(prevTemplate.copy(priorityOrder = updatedPrevOrder))
        }
    }

    suspend fun movePromptTemplateDown(template: PromptTemplate) {
        val templates = promptTemplateDao.getAllTemplates()
        val currentIndex = templates.indexOfFirst { it.id == template.id }
        if (currentIndex != -1 && currentIndex < templates.size - 1) {
            val nextTemplate = templates[currentIndex + 1]
            val updatedNextOrder = template.priorityOrder
            val updatedCurrentOrder = nextTemplate.priorityOrder
            promptTemplateDao.insertTemplate(template.copy(priorityOrder = updatedCurrentOrder))
            promptTemplateDao.insertTemplate(nextTemplate.copy(priorityOrder = updatedNextOrder))
        }
    }

    suspend fun deletePromptTemplate(id: Int) {
        promptTemplateDao.deleteTemplateById(id)
    }

    suspend fun duplicatePromptTemplate(template: PromptTemplate) {
        val existing = promptTemplateDao.getAllTemplates()
        val order = if (existing.isEmpty()) 0 else (existing.maxOfOrNull { it.priorityOrder } ?: 0) + 1
        promptTemplateDao.insertTemplate(
            PromptTemplate(title = "${template.title} (کپی)", promptContent = template.promptContent, priorityOrder = order)
        )
    }
}
