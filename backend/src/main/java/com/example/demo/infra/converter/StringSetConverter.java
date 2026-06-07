package com.example.demo.infra.converter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.util.StringUtils;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * CQRS 視圖降維轉換器：Set<String> <-> 逗號分隔字串
 */
@Converter
public class StringSetConverter implements AttributeConverter<Set<String>, String> {

	private static final String DELIMITER = ",";

	@Override
	public String convertToDatabaseColumn(Set<String> attribute) {
		if (attribute == null || attribute.isEmpty()) {
			return "";
		}
		// 寫入 DB 時：轉成 "task-A,task-B"
		return String.join(DELIMITER, attribute);
	}

	@Override
	public Set<String> convertToEntityAttribute(String dbData) {
		if (!StringUtils.hasText(dbData)) {
			return new HashSet<>();
		}
		// 從 DB 讀取時：轉回 Set<String>
		return Arrays.stream(dbData.split(DELIMITER)).collect(Collectors.toSet());
	}
}