package com.songly.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.community.dto.CommunityResponse;
import com.songly.profile.ProfileDirectory;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommunityServiceTest {

	@Mock
	private CommunityRepository communityRepository;

	@Mock
	private CommunityMemberRepository memberRepository;

	@Mock
	private ProfileDirectory profileDirectory;

	private CommunityService communityService;

	private final UUID ownerId = UUID.randomUUID();
	private final UUID otherId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		communityService = new CommunityService(communityRepository, memberRepository, profileDirectory);
	}

	private Community community() {
		return new Community(UUID.randomUUID(), "Metalheads", "opis", ownerId, Instant.now());
	}

	@Test
	void create_savesCommunityWithOwnerMembership() {
		when(communityRepository.existsByNameIgnoreCase("Metalheads")).thenReturn(false);

		CommunityResponse response = communityService.create(ownerId, "Metalheads", "opis");

		verify(communityRepository).save(any(Community.class));
		verify(memberRepository).save(any(CommunityMember.class));
		assertThat(response.myRole()).isEqualTo(CommunityRole.OWNER);
		assertThat(response.memberCount()).isEqualTo(1);
	}

	@Test
	void create_throws_whenNameTaken() {
		when(communityRepository.existsByNameIgnoreCase("Metalheads")).thenReturn(true);

		assertThatThrownBy(() -> communityService.create(ownerId, "Metalheads", null))
				.isInstanceOf(CommunityNameTakenException.class);

		verify(communityRepository, never()).save(any());
	}

	@Test
	void update_throws_whenRequesterIsNotOwner() {
		Community community = community();
		when(communityRepository.findById(community.getId())).thenReturn(Optional.of(community));

		assertThatThrownBy(() -> communityService.update(community.getId(), otherId, "Nowa", null))
				.isInstanceOf(NotCommunityOwnerException.class);
	}

	@Test
	void update_allowsKeepingOwnName() {
		Community community = community();
		when(communityRepository.findById(community.getId())).thenReturn(Optional.of(community));
		when(memberRepository.countByCommunityId(community.getId())).thenReturn(5L);
		when(memberRepository.findById(any())).thenReturn(
				Optional.of(new CommunityMember(community.getId(), ownerId, CommunityRole.OWNER, Instant.now())));

		CommunityResponse response = communityService.update(community.getId(), ownerId, "metalheads", "nowy opis");

		assertThat(response.name()).isEqualTo("metalheads");
		assertThat(response.description()).isEqualTo("nowy opis");
	}

	@Test
	void update_throws_whenNewNameTaken() {
		Community community = community();
		when(communityRepository.findById(community.getId())).thenReturn(Optional.of(community));
		when(communityRepository.existsByNameIgnoreCase("Zajęta")).thenReturn(true);

		assertThatThrownBy(() -> communityService.update(community.getId(), ownerId, "Zajęta", null))
				.isInstanceOf(CommunityNameTakenException.class);
	}

	@Test
	void delete_throws_whenRequesterIsNotOwner() {
		Community community = community();
		when(communityRepository.findById(community.getId())).thenReturn(Optional.of(community));

		assertThatThrownBy(() -> communityService.delete(community.getId(), otherId))
				.isInstanceOf(NotCommunityOwnerException.class);

		verify(communityRepository, never()).delete(any(Community.class));
	}

	@Test
	void join_isIdempotent() {
		Community community = community();
		when(communityRepository.findById(community.getId())).thenReturn(Optional.of(community));
		when(memberRepository.existsById(new CommunityMemberId(community.getId(), otherId))).thenReturn(true);

		communityService.join(community.getId(), otherId);

		verify(memberRepository, never()).save(any());
	}

	@Test
	void join_throws_whenCommunityMissing() {
		UUID communityId = UUID.randomUUID();
		when(communityRepository.findById(communityId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> communityService.join(communityId, otherId))
				.isInstanceOf(CommunityNotFoundException.class);
	}

	@Test
	void leave_throws_whenOwnerTriesToLeave() {
		Community community = community();
		when(communityRepository.findById(community.getId())).thenReturn(Optional.of(community));
		when(memberRepository.findById(new CommunityMemberId(community.getId(), ownerId))).thenReturn(
				Optional.of(new CommunityMember(community.getId(), ownerId, CommunityRole.OWNER, Instant.now())));

		assertThatThrownBy(() -> communityService.leave(community.getId(), ownerId))
				.isInstanceOf(OwnerCannotLeaveException.class);
	}

	@Test
	void leave_removesMembership() {
		Community community = community();
		CommunityMember membership = new CommunityMember(community.getId(), otherId, CommunityRole.MEMBER,
				Instant.now());
		when(communityRepository.findById(community.getId())).thenReturn(Optional.of(community));
		when(memberRepository.findById(new CommunityMemberId(community.getId(), otherId)))
				.thenReturn(Optional.of(membership));

		communityService.leave(community.getId(), otherId);

		verify(memberRepository).delete(membership);
	}

	@Test
	void leave_isNoOp_whenNotMember() {
		Community community = community();
		when(communityRepository.findById(community.getId())).thenReturn(Optional.of(community));
		when(memberRepository.findById(new CommunityMemberId(community.getId(), otherId)))
				.thenReturn(Optional.empty());

		communityService.leave(community.getId(), otherId);

		verify(memberRepository, never()).delete(any(CommunityMember.class));
	}
}
