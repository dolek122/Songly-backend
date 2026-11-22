package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class BanAppealNotEscalatableException extends ApiException {

	BanAppealNotEscalatableException() {
		super(HttpStatus.CONFLICT, "BAN_APPEAL_NOT_ESCALATABLE",
				"Do administracji można przekazać tylko odwołanie odrzucone przez moderację społeczności");
	}
}
