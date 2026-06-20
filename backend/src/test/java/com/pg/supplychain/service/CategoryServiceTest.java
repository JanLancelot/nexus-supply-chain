package com.pg.supplychain.service;

import com.pg.supplychain.dto.CategoryRequest;
import com.pg.supplychain.dto.CategoryResponse;
import com.pg.supplychain.exception.BadRequestException;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.CategoryEvent;
import com.pg.supplychain.model.Category;
import com.pg.supplychain.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private KafkaLiteBroker kafkaLiteBroker;

    @InjectMocks
    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetAllCategories() {
        Category c1 = Category.builder().id(UUID.randomUUID()).name("Electronics").description("Gadgets").build();
        Category c2 = Category.builder().id(UUID.randomUUID()).name("Office").description("Paper").build();
        when(categoryRepository.findAll()).thenReturn(Arrays.asList(c1, c2));

        List<CategoryResponse> result = categoryService.getAllCategories();
        assertEquals(2, result.size());
        assertEquals("Electronics", result.get(0).getName());
        assertEquals("Office", result.get(1).getName());
    }

    @Test
    void testGetCategoryById() {
        UUID id = UUID.randomUUID();
        Category c = Category.builder().id(id).name("Electronics").build();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(c));

        CategoryResponse response = categoryService.getCategoryById(id);
        assertNotNull(response);
        assertEquals("Electronics", response.getName());
    }

    @Test
    void testGetCategoryById_NotFound() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> categoryService.getCategoryById(id));
    }

    @Test
    void testCreateCategory_Success() {
        CategoryRequest request = new CategoryRequest("Electronics", "Gadgets");
        when(categoryRepository.findByName("Electronics")).thenReturn(Optional.empty());

        UUID generatedId = UUID.randomUUID();
        Category saved = Category.builder().id(generatedId).name("Electronics").description("Gadgets").build();
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);

        CategoryResponse response = categoryService.createCategory(request);
        assertNotNull(response);
        assertEquals(generatedId, response.getId());
        assertEquals("Electronics", response.getName());

        verify(kafkaLiteBroker, times(1)).send(eq("category-events"), any(CategoryEvent.class));
    }

    @Test
    void testCreateCategory_Conflict() {
        CategoryRequest request = new CategoryRequest("Electronics", "Gadgets");
        Category existing = Category.builder().id(UUID.randomUUID()).name("Electronics").build();
        when(categoryRepository.findByName("Electronics")).thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class, () -> categoryService.createCategory(request));
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void testUpdateCategory_Success() {
        UUID id = UUID.randomUUID();
        Category existing = Category.builder().id(id).name("Old Name").build();
        CategoryRequest request = new CategoryRequest("New Name", "Desc");

        when(categoryRepository.findById(id)).thenReturn(Optional.of(existing));
        when(categoryRepository.findByName("New Name")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryResponse response = categoryService.updateCategory(id, request);
        assertNotNull(response);
        assertEquals("New Name", response.getName());
        verify(kafkaLiteBroker, times(1)).send(eq("category-events"), any(CategoryEvent.class));
    }

    @Test
    void testDeleteCategory_Success() {
        UUID id = UUID.randomUUID();
        Category existing = Category.builder().id(id).name("Electronics").build();

        when(categoryRepository.findById(id)).thenReturn(Optional.of(existing));

        categoryService.deleteCategory(id);

        verify(categoryRepository, times(1)).delete(existing);
        verify(kafkaLiteBroker, times(1)).send(eq("category-events"), any(CategoryEvent.class));
    }
}
