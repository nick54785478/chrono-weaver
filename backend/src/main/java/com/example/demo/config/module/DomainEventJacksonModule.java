package com.example.demo.config.module;

import com.example.demo.application.domain.team.event.ProjectTeamEvent;
import com.example.demo.infra.event.mixin.ProjectTeamEventMixIn;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * 註冊所有的 Domain Event MixIn
 */
public class DomainEventJacksonModule extends SimpleModule {

	/**
	 * 
	 */
	private static final long serialVersionUID = -1903129351249709501L;

	public DomainEventJacksonModule() {
		super("CQRSJacksonModule");

		// 核心魔法：告訴 Jackson，當你遇到 ProjectTeamEvent 時，請去讀 ProjectTeamEventMixIn 身上的註解！
		setMixInAnnotation(ProjectTeamEvent.class, ProjectTeamEventMixIn.class);

		// 💡 未來如果有其他聚合的 Event (例如 ProjectEvent)，也可以統一寫在這裡
		// setMixInAnnotation(ProjectEvent.class, ProjectEventMixIn.class);
	}
}