export interface SchoolYearRequest {
  startYear: number;
  endYear: number;
}

export interface SchoolYearResponse {
  id: number;           
  startYear: number;
  endYear: number;
  createdAt: string; 
  updatedAt: string;
  deletedAt: string | null;
}

export type SchoolYearList = SchoolYearResponse[];