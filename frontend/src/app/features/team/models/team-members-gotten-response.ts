import { TeamMember } from '../../../shared/models/team-member';

export interface TeamMembersGottenResult {
  code: string;
  message: string;
  data: TeamMember[];
}
