library(dplyr)
library(ggplot2)

MAX_LATENCY = 5

orchestrator <- read.csv("orchestrator_10ms_up_down.csv",	sep	=	";")
choreography <- read.csv("choreography_10ms_up_down.csv", sep = ";")

o <- orchestrator %>%
  filter(simulated_latency <= MAX_LATENCY) %>%
  group_by(simulated_latency) %>%
  summarize(
    type = "O",
    subtype = "Network",
    time = mean(end_to_end_time) / 1000000,
  )

c1 <- choreography %>%
  filter(simulated_latency <= MAX_LATENCY) %>%
  group_by(simulated_latency) %>%
  summarize(
    type = "A",
    subtype = "Sidecar",
    time = mean(sidecar) / 1000000,
  )

c2 <- choreography %>%
  filter(simulated_latency <= MAX_LATENCY) %>%
  group_by(simulated_latency) %>%
  summarize(
    type = "A",
    subtype = "Network",
    time = (mean(total) - mean(sidecar)) / 1000000,
  )

data <- bind_rows(o, c1, c2)

milliseconds <- function(x) {
  paste(x, "ms")
}

p <- ggplot(data, aes(x = type, y = time, fill=subtype)) +
  geom_bar(position="stack", stat="identity") +
  scale_fill_manual(values=c("#F15350", "#38B84D")) +
  facet_grid(~ simulated_latency, labeller = as_labeller(milliseconds)) +
  theme(
    text = element_text(size = 20),
    #strip.placement = "outside",
    strip.background = element_rect(fill = NA, color = "white"),
    #panel.spacing = unit(-.01,"cm")
    #axis.text.x = element_text(angle = 45, vjust = 1, hjust=1)
    plot.subtitle = element_text(hjust = 0.5),
  ) +
  labs(
    # title = "Title",
    subtitle = "Network latency",
    # caption = "Caption",
    # tag = "Tag",
    # alt = "Alt",
    # alt_insight = "Alt insight"
    fill = NULL,
    x = NULL, # "Network latency",
    y = "End-to-end latency",
  ) +
  scale_y_continuous(labels = milliseconds)

plot(p)










