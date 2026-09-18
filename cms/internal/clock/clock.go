package clock

func MasterNowMs() int64 {
	return platformNowMs()
}
