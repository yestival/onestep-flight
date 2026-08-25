package com.selene.onestepflight.service;

import com.selene.onestepflight.dto.ActionCode;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ActionCodeAttributeConverter implements AttributeConverter<ActionCode, String> {

	@Override
	public String convertToDatabaseColumn(ActionCode actionCode) {
		return actionCode == null ? null : actionCode.name();
	}

	@Override
	public ActionCode convertToEntityAttribute(String actionCode) {
		return actionCode == null ? null : ActionCode.valueOf(actionCode);
	}
}
